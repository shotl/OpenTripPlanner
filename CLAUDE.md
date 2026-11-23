# OpenTripPlanner, Shotl fork

This repository is Shotl's fork of OpenTripPlanner (OTP). It adds a demand-responsive transport
(DRT) mode to the routing engine, integrated with the Shotl ride service: an on-demand shared
vehicle takes the rider to a transit stop (access), away from a transit stop (egress), or door to
door (direct). The OTP version the fork is based on is the `<version>` in the root `pom.xml`.

The design of the DRT mode is in `DRT_Design.md`. It is read on demand: before you change DRT
behaviour, read the section that covers the change (table below).

## Branches and remotes

- The mainline is `feature/drt-mode`. Branch from it and open pull requests into it. There is no
  `staging` branch.
- The default branch on GitHub is `dev-2.x`, so name the base when you open a pull request:
  `gh pr create --base feature/drt-mode`.
- A push to `feature/drt-mode` deploys to staging, and a `v*` tag deploys to production
  (`.github/workflows/deploy.yaml`).
- Upstream is the remote whose URL is `opentripplanner/OpenTripPlanner` (see `git remote -v`;
  remote names differ per clone). Its `dev-2.x` is the upstream development branch. The `dev-2.x`
  on the Shotl remote is a copy of upstream and holds no Shotl work.
- Syncing with upstream is a deliberate operation. Ask before you merge, rebase onto or
  cherry-pick from upstream.

## Build, test, format

Java 21 (`<release>` of `maven-compiler-plugin` in the root `pom.xml`).

- Runnable jar: `mvn -q package -pl otp-shaded -am -DskipTests -Dplugin.prettier.skip=true`.
  The result is `otp-shaded/target/otp-shaded-<version>.jar`.
- One test class: `mvn -q -pl application test -Dtest=<Class>`. With `-pl application` alone,
  Maven does not build the `utils`, `raptor` and `gtfs-realtime-protobuf` modules; it takes them
  from the local Maven repository, so they have to be installed there.
- Formatting is prettier-java through `prettier-maven-plugin`: `mvn prettier:write -pl application`.
  The plugin is bound to the `validate` phase with the `write` goal, so any Maven run rewrites
  the sources in place unless you pass `-Dplugin.prettier.skip=true`.

## Where the DRT code is

- The extension package: `application/src/ext/java/org/opentripplanner/ext/demandresponsivetransportation/`
  (Shotl rides client in `service/shotl/`, Shotl areas client in `service/shotlareas/`).
- Touch points in core OTP files: search `application/src/main` and `raptor/src/main`
  case-insensitively for `demand.?responsive|drt|shotl`. The class-name token
  `DemandResponsiveTransportation` alone finds only part of them.
- Some changed core files carry no DRT token. The complete list of what the fork changes is
  `git diff --name-status $(git merge-base HEAD <remote>/dev-2.x) HEAD`.
- Keep changes inside the DRT code paths, so that the next upstream sync stays mergeable. Do not
  refactor or rename upstream code.

## Contracts

- `shotl-rides` is the consumer of the GraphQL API: it sends the `planConnection` query of the
  GTFS API. Its client is `internal/infrastructure/rest/opentripplanner.go` in the `shotl-rides`
  repository.
- The DRT fields are in both schema files:
  `application/src/main/resources/org/opentripplanner/apis/gtfs/schema.graphqls` and
  `application/src/main/resources/org/opentripplanner/apis/transmodel/schema.graphql`.
  The GTFS Java types in `application/src/main/java/org/opentripplanner/apis/gtfs/generated/`
  are generated from the schema (see the `README.md` there).
- OTP calls two Shotl services back: the rides service for time estimates (`estimationsURL`,
  client `ShotlService`) and the areas service for journey availability (`areasURL`, client
  `ShotlAreasService`). Both URLs are set under `demandResponsiveTransportationServices` in
  `config/<environment>/router-config.json` and parsed by
  `DemandResponsiveTransportationServicesConfig` and `services/ShotlConfig` under
  `application/src/main/java/org/opentripplanner/standalone/config/routerconfig/`.
- A change to either side of a contract needs the other side changed in the same piece of work.

## Read before you change

Sections of `DRT_Design.md`, by title:

| Change | Section |
|--------|---------|
| Overall request flow | 1. High-Level Architecture |
| GraphQL request, `drt` input | 2. GraphQL Request Format |
| Street routing, access and egress search | 3. Phase 1: Street Routing (A*) |
| Access shifting | 4. Phase 2: Access Shifting (Pre-Raptor) |
| Raptor costs | 5. Phase 3: Raptor Transit Routing |
| Itinerary decoration | 6. Phase 4: Itinerary Decoration (Post-Raptor) |
| Direct DRT | 7. Phase 5: Direct DRT (Door-to-Door) |
| Shotl API client | 8. Shotl API Integration |
| Cost formula and reluctances | 9. Cost Calculation (Generalized Cost) |
| Penalties | 10. Penalties (AccessEgressPenaltyDecorator) |
| Temporal overlap | 11. Temporal Overlap Fixing |
| Filters | 12. Filtering |
| Eligible stops and siblings | 13. DRT-Eligible Stops and Sibling Expansion |
| Caching | 14. Caching (Per-Request) |
| GraphQL response | 15. GraphQL Response |
| Configuration | 16. Configuration |
| Finding a file | 18. Key Source Files |
| Performance | 20. Performance: Current Optimizations and Improvement Opportunities |
| Waiting time | 21. DRT waiting time |
| Journey-availability pre-filter (Shotl areas service) | 22. Journey-Availability Pre-Filter |

The design can lag behind the code. Check a statement against the source before you rely on it,
and correct the design in the same change when it is wrong.

Other documents at the repository root:

- `DRT_GraphQL_API_Guide.md`: the input parameters of the `planConnection` query, the DRT input
  and response fields, and example queries.
- `DRT_Reluctance_Guide.md`: how reluctance turns travel time into generalized cost, the default
  values per mode, and how they affect DRT.
- `DRT_Pareto_Optimal.md`: how Raptor's Pareto filtering decides which DRT egress itineraries
  survive.
- `DRT_Feature_Analysis.md`: an analysis of the DRT implementation against OTP's ride-hailing
  feature, with gaps and recommendations.
- `DRT_Improvement_Opportunities.md`: a backlog of possible refactors. Nothing in it is to be
  applied unless asked.

## Tests

The DRT tests are in
`application/src/ext-test/java/org/opentripplanner/ext/demandresponsivetransportation/`.
Run one with the test command above, for example
`mvn -q -pl application test -Dtest=DRTLegWaitingTimeTest`.
