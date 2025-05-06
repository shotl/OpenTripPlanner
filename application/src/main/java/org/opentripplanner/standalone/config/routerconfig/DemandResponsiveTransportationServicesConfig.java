package org.opentripplanner.standalone.config.routerconfig;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_3;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.util.List;
import java.util.function.Function;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationServiceParameters;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;
import org.opentripplanner.standalone.config.routerconfig.services.ShotlConfig;

public class DemandResponsiveTransportationServicesConfig {

  private final Multimap<Type, Object> configList = ArrayListMultimap.create();

  public DemandResponsiveTransportationServicesConfig(NodeAdapter rootAdapter) {
    rootAdapter
      .of("demandResponsiveTransportationServices")
      .since(V2_3)
      .summary("Configuration for interfaces to external demand responsive transportation services like Shotl.")
      .asObjects(it -> {
        Type type = it
          .of("type")
          .since(V2_3)
          .summary("The type of the service.")
          .asEnum(Type.class);
        var config = type.parseConfig(it);
        configList.put(type, config);
        // We do not care what we return here
        return config;
      });
  }

  public List<DemandResponsiveTransportationServiceParameters> demandResponsiveTransportationServiceParameters() {
    return configList
      .values()
      .stream()
      .filter(DemandResponsiveTransportationServiceParameters.class::isInstance)
      .map(DemandResponsiveTransportationServiceParameters.class::cast)
      .toList();
  }

  public enum Type {
    SHOTL_DEMAND_RESPONSIVE_TRANSPORTATION(ShotlConfig::create);

    private final Function<NodeAdapter, ?> factory;

    Type(Function<NodeAdapter, ?> factory) {
      this.factory = factory;
    }

    Object parseConfig(NodeAdapter nodeAdapter) {
      return factory.apply(nodeAdapter);
    }
  }
}
