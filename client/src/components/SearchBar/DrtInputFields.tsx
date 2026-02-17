import { Form } from 'react-bootstrap';
import { TripQueryVariables } from '../../gql/graphql.ts';
import { StreetMode } from '../../gql/graphql.ts';

function isDrtSelected(tripQueryVariables: TripQueryVariables): boolean {
  const drtMode = StreetMode.DemandResponsiveTransportation;
  return (
    tripQueryVariables.modes?.accessMode === drtMode ||
    tripQueryVariables.modes?.egressMode === drtMode ||
    tripQueryVariables.modes?.directMode === drtMode
  );
}

export function DrtInputFields({
  tripQueryVariables,
  setTripQueryVariables,
}: {
  tripQueryVariables: TripQueryVariables;
  setTripQueryVariables: (tripQueryVariables: TripQueryVariables) => void;
}) {
  if (!isDrtSelected(tripQueryVariables)) {
    return null;
  }

  const drt = tripQueryVariables.drt || {};

  const updateDrt = (
    field: string,
    value: string | number | null | { regular?: number | null; wheelchair?: number | null },
  ) => {
    setTripQueryVariables({
      ...tripQueryVariables,
      drt: {
        ...drt,
        [field]: value,
      },
    });
  };

  return (
    <div className="input-family">
      <Form.Label column="sm">
        <b>DRT Configuration</b>
      </Form.Label>
      <Form.Group>
        <Form.Label column="sm" htmlFor="drtAreaId">
          Area ID
        </Form.Label>
        <Form.Control
          id="drtAreaId"
          size="sm"
          type="text"
          value={drt.areaId || ''}
          onChange={(e) => updateDrt('areaId', e.target.value || null)}
        />
      </Form.Group>
      <Form.Group>
        <Form.Label column="sm" htmlFor="drtPaxAppId">
          Pax App ID
        </Form.Label>
        <Form.Control
          id="drtPaxAppId"
          size="sm"
          type="text"
          value={drt.paxAppId || ''}
          onChange={(e) => updateDrt('paxAppId', e.target.value || null)}
        />
      </Form.Group>
      <Form.Group>
        <Form.Label column="sm" htmlFor="drtUserId">
          User ID
        </Form.Label>
        <Form.Control
          id="drtUserId"
          size="sm"
          type="text"
          value={drt.userId || ''}
          onChange={(e) => updateDrt('userId', e.target.value || null)}
        />
      </Form.Group>
      <Form.Group>
        <Form.Label column="sm" htmlFor="drtRideType">
          Ride Type
        </Form.Label>
        <Form.Control
          id="drtRideType"
          size="sm"
          type="text"
          value={drt.rideType || ''}
          onChange={(e) => updateDrt('rideType', e.target.value || null)}
        />
      </Form.Group>
      <Form.Group>
        <Form.Label column="sm" htmlFor="drtPassengersRegular">
          Regular Passengers
        </Form.Label>
        <Form.Control
          id="drtPassengersRegular"
          size="sm"
          type="number"
          min={0}
          value={drt.passengers?.regular ?? 1}
          onChange={(e) =>
            updateDrt('passengers', {
              ...drt.passengers,
              regular: parseInt(e.target.value) || 1,
            })
          }
        />
      </Form.Group>
      <Form.Group>
        <Form.Label column="sm" htmlFor="drtPassengersWheelchair">
          Wheelchair Passengers
        </Form.Label>
        <Form.Control
          id="drtPassengersWheelchair"
          size="sm"
          type="number"
          min={0}
          value={drt.passengers?.wheelchair ?? 0}
          onChange={(e) =>
            updateDrt('passengers', {
              ...drt.passengers,
              wheelchair: parseInt(e.target.value) || 0,
            })
          }
        />
      </Form.Group>
    </div>
  );
}
