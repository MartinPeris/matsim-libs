package org.matsim.contrib.parking.parkingsearchparameterization;

/**
 * Parameters for deriving kerb parking supply on links that carry no {@code onstreet_spots} attribute.
 *
 * @param bayLengthMetres kerb length one parked vehicle occupies; a link offers {@code floor(length / bayLength)}
 *                        spaces when it is eligible
 */
public record KerbParkingSupplyParams(double bayLengthMetres) {
	/** A common planning value for a parallel kerbside bay. */
	public static final double DEFAULT_BAY_LENGTH_METRES = 6.0;

	public KerbParkingSupplyParams {
		if (!(bayLengthMetres > 0)) {
			throw new IllegalArgumentException("bayLengthMetres must be positive, got " + bayLengthMetres);
		}
	}

	public static KerbParkingSupplyParams defaults() {
		return new KerbParkingSupplyParams(DEFAULT_BAY_LENGTH_METRES);
	}
}
