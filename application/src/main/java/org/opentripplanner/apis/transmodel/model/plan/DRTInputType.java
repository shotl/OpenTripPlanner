package org.opentripplanner.apis.transmodel.model.plan;

import graphql.Scalars;
import graphql.schema.GraphQLInputObjectType;

public class DRTInputType {

  public static final GraphQLInputObjectType PASSENGERS_INPUT_TYPE =
    GraphQLInputObjectType.newInputObject()
      .name("DRTPassengersInput")
      .description("Passenger information for a DRT ride.")
      .field(f ->
        f.name("regular").description("Number of regular passengers.").type(Scalars.GraphQLInt)
      )
      .field(f ->
        f
          .name("wheelchair")
          .description("Number of wheelchair passengers.")
          .type(Scalars.GraphQLInt)
      )
      .build();

  public static final GraphQLInputObjectType INPUT_TYPE = GraphQLInputObjectType.newInputObject()
    .name("DRTInput")
    .description("Input for DRT (Demand Responsive Transport) ride request.")
    .field(f ->
      f.name("areaId").description("Area ID for the DRT service.").type(Scalars.GraphQLString)
    )
    .field(f -> f.name("paxAppId").description("Passenger app ID.").type(Scalars.GraphQLString))
    .field(f ->
      f.name("userId").description("User ID for the DRT service.").type(Scalars.GraphQLString)
    )
    .field(f ->
      f.name("rideType").description("Type of ride requested.").type(Scalars.GraphQLString)
    )
    .field(f ->
      f
        .name("passengers")
        .description("Passenger information for the DRT ride.")
        .type(PASSENGERS_INPUT_TYPE)
    )
    .field(f ->
      f
        .name("egressReluctance")
        .description(
          "Reluctance multiplier applied to CAR-based egress generalized cost before RAPTOR routing. " +
          "A value of 1.0 (default) means no cost inflation. Values greater than 1.0 make DRT egress " +
          "paths appear more expensive, compensating for the fact that real DRT travel times are " +
          "typically longer than plain CAR routing estimates."
        )
        .type(Scalars.GraphQLFloat)
        .defaultValueLiteral(graphql.language.FloatValue.of(1.0))
    )
    .build();
}
