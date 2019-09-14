package ch.ethz.matsim.av.framework;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.optimizer.VrpOptimizer;
import org.matsim.contrib.dvrp.passenger.PassengerEngineQSimModule;
import org.matsim.contrib.dvrp.passenger.PassengerRequestCreator;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpMode;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.contrib.dvrp.vrpagent.VrpAgentLogic.DynActionCreator;
import org.matsim.contrib.dvrp.vrpagent.VrpLeg;
import org.matsim.contrib.dvrp.vrpagent.VrpLegFactory;
import org.matsim.contrib.dynagent.run.DynActivityEngineModule;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.components.QSimComponentsConfig;

import com.google.inject.Provides;
import com.google.inject.Singleton;

import ch.ethz.matsim.av.config.AVConfigGroup;
import ch.ethz.matsim.av.config.operator.DispatcherConfig;
import ch.ethz.matsim.av.config.operator.OperatorConfig;
import ch.ethz.matsim.av.data.AVData;
import ch.ethz.matsim.av.data.AVOperator;
import ch.ethz.matsim.av.data.AVVehicle;
import ch.ethz.matsim.av.dispatcher.AVDispatcher;
import ch.ethz.matsim.av.dispatcher.AVDispatchmentListener;
import ch.ethz.matsim.av.generator.AVGenerator;
import ch.ethz.matsim.av.passenger.AVRequestCreator;
import ch.ethz.matsim.av.router.AVRouter;
import ch.ethz.matsim.av.schedule.AVOptimizer;
import ch.ethz.matsim.av.schedule.AVStayTask;
import ch.ethz.matsim.av.vrpagent.AVActionCreator;
import ch.ethz.matsim.av.vrpagent.AVAgentSource;

public class AVQSimModule extends AbstractDvrpModeQSimModule {
	public final static String COMPONENT_NAME = "AVExtension";

	public static void configureComponents(QSimComponentsConfig components) {
		DynActivityEngineModule.configureComponents(components);
		// components.addNamedComponent(COMPONENT_NAME);
		// components.addNamedComponent(AVModule.AV_MODE);
		components.addComponent(DvrpModes.mode(AVModule.AV_MODE));
	}

	public AVQSimModule() {
		super(AVModule.AV_MODE);
	}

	@Override
	protected void configureQSim() {
		// install(new VrpAgentSourceQSimModule(getMode()));
		install(new PassengerEngineQSimModule(getMode()));

		bindModal(PassengerRequestCreator.class).to(AVRequestCreator.class);
		bindModal(DynActionCreator.class).to(AVActionCreator.class);
		bindModal(VrpOptimizer.class).to(AVOptimizer.class);

		bind(AVOptimizer.class);
		bind(AVDispatchmentListener.class);

		addModalQSimComponentBinding().to(AVDispatchmentListener.class);
		addModalQSimComponentBinding().to(AVOptimizer.class);

		bindModal(AVDispatchmentListener.class).to(AVDispatchmentListener.class);
		bindModal(Fleet.class).to(AVData.class);

		addModalQSimComponentBinding().to(AVAgentSource.class);
	}

	@Provides
	AVAgentSource provideAgentSource(@DvrpMode(AVModule.AV_MODE) DynActionCreator actionCreator,
			@DvrpMode(AVModule.AV_MODE) Fleet data, @DvrpMode(AVModule.AV_MODE) VrpOptimizer optimizer, QSim qsim) {
		return new AVAgentSource(actionCreator, data, optimizer, qsim);
	}

	@Provides
	@Singleton
	VrpLegFactory provideLegCreator(AVOptimizer avOptimizer, QSim qsim) {
		return new VrpLegFactory() {
			@Override
			public VrpLeg create(DvrpVehicle vehicle) {
				return VrpLegFactory.createWithOnlineTracker(TransportMode.car, vehicle, avOptimizer,
						qsim.getSimTimer());
			}
		};
	}

	@Provides
	@Singleton
	Map<Id<AVOperator>, AVDispatcher> provideDispatchers(Map<String, AVDispatcher.AVDispatcherFactory> factories,
			Map<Id<AVOperator>, AVRouter> routers, AVConfigGroup config, Map<Id<AVOperator>, List<AVVehicle>> vehicles,
			Map<Id<AVOperator>, Network> networks) {
		Map<Id<AVOperator>, AVDispatcher> dispatchers = new HashMap<>();

		for (OperatorConfig oc : config.getOperatorConfigs().values()) {
			DispatcherConfig dc = oc.getDispatcherConfig();
			String strategy = dc.getType();

			if (!factories.containsKey(strategy)) {
				throw new IllegalArgumentException("Dispatcher strategy '" + strategy + "' is not registered.");
			}

			AVRouter router = routers.get(oc.getId());
			Network network = networks.get(oc.getId());

			AVDispatcher.AVDispatcherFactory factory = factories.get(strategy);
			AVDispatcher dispatcher = factory.createDispatcher(oc, router, network);

			for (AVVehicle vehicle : vehicles.get(oc.getId())) {
				dispatcher.addVehicle(vehicle);
				vehicle.setDispatcher(dispatcher);
			}

			dispatchers.put(oc.getId(), dispatcher);
		}

		return dispatchers;
	}

	@Provides
	@Singleton
	public Map<Id<AVOperator>, List<AVVehicle>> provideVehicles(Map<Id<AVOperator>, AVOperator> operators,
			Map<Id<AVOperator>, AVGenerator> generators) {
		Map<Id<AVOperator>, List<AVVehicle>> vehicles = new HashMap<>();

		for (AVOperator operator : operators.values()) {
			LinkedList<AVVehicle> operatorList = new LinkedList<>();

			AVGenerator generator = generators.get(operator.getId());

			while (generator.hasNext()) {
				AVVehicle vehicle = generator.next();
				vehicle.setOperator(operator);
				operatorList.add(vehicle);

				if (Double.isFinite(vehicle.getServiceEndTime())) {
					throw new IllegalStateException("AV vehicles must have infinite service time");
				}
			}

			vehicles.put(operator.getId(), operatorList);
		}

		return vehicles;
	}

	@Provides
	@Singleton
	public AVData provideData(Map<Id<AVOperator>, AVOperator> operators,
			Map<Id<AVOperator>, List<AVVehicle>> vehicles) {
		AVData data = new AVData();

		for (List<AVVehicle> vehs : vehicles.values()) {
			for (AVVehicle vehicle : vehs) {
				data.addVehicle(vehicle);

				vehicle.getSchedule().addTask(new AVStayTask(vehicle.getServiceBeginTime(), vehicle.getServiceEndTime(),
						vehicle.getStartLink()));
			}
		}

		return data;
	}
}
