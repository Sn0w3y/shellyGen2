package io.openems.edge.io.shelly.shelly1pm;

import java.util.Objects;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.io.api.DigitalOutput;
import io.openems.edge.io.shelly.common.ShellyApi;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.MeterType;
import io.openems.edge.meter.api.SinglePhase;
import io.openems.edge.meter.api.SinglePhaseMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "IO.Shelly.2ndGen", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE//
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})
public class IoShelly1PMImpl extends AbstractOpenemsComponent
		implements IoShelly1PM, DigitalOutput, SinglePhaseMeter, ElectricityMeter, OpenemsComponent, EventHandler {

	private final Logger log = LoggerFactory.getLogger(IoShelly1PMImpl.class);
	private final BooleanWriteChannel[] digitalOutputChannels;

	private ShellyApi shellyApi = null;
	private MeterType meterType = null;
	private SinglePhase phase = null;

	public IoShelly1PMImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				DigitalOutput.ChannelId.values(), //
				IoShelly1PM.ChannelId.values() //
		);
		this.digitalOutputChannels = new BooleanWriteChannel[] { //
				this.channel(IoShelly1PM.ChannelId.RELAY) //
		};

		SinglePhaseMeter.calculateSinglePhaseFromActivePower(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.shellyApi = new ShellyApi(config.ip());
		this.meterType = config.type();
		this.phase = config.phase();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public BooleanWriteChannel[] digitalOutputChannels() {
		return this.digitalOutputChannels;
	}

	@Override
	public String debugLog() {
		var b = new StringBuilder();
		var valueOpt = this.getRelayChannel().value().asOptional();
		if (valueOpt.isPresent()) {
			b.append(valueOpt.get() ? "On" : "Off");
		} else {
			b.append("Unknown");
		}
		b.append("|");
		b.append(this.getActivePowerChannel().value().asString());
		return b.toString();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}

		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			this.eventBeforeProcessImage();
			break;

		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:
			this.eventExecuteWrite();
			break;
		}
	}

	private void eventBeforeProcessImage() {
	    Boolean relayIson = null;
	    Float apower = null;
	    Long aenergyTotal = null;

	    try {
	        var json = this.shellyApi.get2ndGenStatus(); // Assuming this is the provided JSON

	        // Parse relay state (assuming 'switch:0' is the relay in the new structure)
	        var switch0 = JsonUtils.getAsJsonObject(json, "switch:0");
	        relayIson = JsonUtils.getAsBoolean(switch0, "output");
	        
	        // Parse power
	        apower = JsonUtils.getAsFloat(switch0, "apower");
	        
	        // Parse energy values (from 'switch:0' in new structure)
	        var aenergy = JsonUtils.getAsJsonObject(switch0, "aenergy");
	        if (aenergy.has("total")) {
	            var aenergyTotalValue = aenergy.get("total");
	            if (aenergyTotalValue.isJsonPrimitive() && aenergyTotalValue.getAsJsonPrimitive().isNumber()) {
	                aenergyTotal = Math.round(aenergyTotalValue.getAsFloat() * 1000) / 60L; // Convert kWh to Wh, and then divide by 60 to get minute-based Wh.
	            }
	        }

	        this._setSlaveCommunicationFailed(false);
	    } catch (OpenemsNamedException e) {
	        this.logError(this.log, "Unable to read from Shelly API: " + e.getMessage());
	        this._setSlaveCommunicationFailed(true);
	    }

	    this._setRelay(relayIson);
	    this._setActivePower(Math.round(apower));
	    this._setActiveProductionEnergy(aenergyTotal);
	}



	/**
	 * Execute on Cycle Event "Execute Write".
	 */
	private void eventExecuteWrite() {
		try {
			this.executeWrite(this.getRelayChannel(), 0);

			this._setSlaveCommunicationFailed(false);
		} catch (OpenemsNamedException e) {
			this._setSlaveCommunicationFailed(true);
		}
	}

	private void executeWrite(BooleanWriteChannel channel, int index) throws OpenemsNamedException {
		var readValue = channel.value().get();
		var writeValue = channel.getNextWriteValueAndReset();
		if (!writeValue.isPresent()) {
			// no write value
			return;
		}
		if (Objects.equals(readValue, writeValue.get())) {
			// read value = write value
			return;
		}
		this.shellyApi.setRelayTurn(index, writeValue.get());
	}

	@Override
	public MeterType getMeterType() {
		return this.meterType;
	}

	@Override
	public SinglePhase getPhase() {
		return this.phase;
	}

}