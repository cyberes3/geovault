"""
Coordinate Parser - parses geographic coordinates in various formats.

Ported from a Node.js library; vendored as a single module under geo_lib so
the backend has zero external dependency on a private PyPI package.

Formats supported:

Decimal degrees:
   23.43
   -45.21

Decimal Degrees with quadrant:
   23.43 N
   45.21 W
   N 23.43
   W 45.21

Degrees, decimal minutes:
  23° 25.800'
  -45° 12.600'
  23 25.800'
  -45 12.600'
  23° 25.8' N
  45° 12.6' W

Degrees, Minutes, Seconds:
   23° 25' 48.0"
  -45° 12' 36.0"
   23d 25' 48.0"
  -45d 12' 36.0"
   23° 25' 48.0" N
  45° 12' 36.0" S

Maritime coordinate formats:
  40°–41.65'N, 139°-02.54'E (degree-dash-minutes with degree symbol)
  54-05.48N, 162-29.03W (degree-dash-minutes without degree symbol)
  30°34.4'N (degree-minutes with degree symbol)
  30°34'24.0"N (degree-minutes-seconds)
"""

import math
import re
from decimal import Decimal

__version__ = "0.1.1"
__all__ = ["parse_coordinate", "to_dec_deg"]


def to_dec_deg(*args: float) -> float:
    """Convert degrees, minutes, seconds to decimal degrees.

    Args:
        *args: Variable arguments representing degrees, minutes (optional),
            seconds (optional)

    Returns:
        Decimal degrees as float
    """
    if len(args) == 1:
        return float(args[0])
    elif len(args) == 2:
        degrees, minutes = args
        return float(degrees) + float(minutes) / 60.0
    elif len(args) == 3:
        degrees, minutes, seconds = args
        return float(degrees) + float(minutes) / 60.0 + float(seconds) / 3600.0
    else:
        raise ValueError("Invalid number of arguments")


def parse_coordinate(
    string: str | float | Decimal | None,
    coord_type: str = "coordinate",
    validate: bool = True,
) -> Decimal | None:
    """
    Attempts to parse a latitude or longitude string with optional validation.

    Returns the value in decimal degrees.

    If parsing fails, it raises a ValueError.

    Args:
        string: The coordinate string to parse
        coord_type: Type of coordinate ('latitude', 'longitude', or 'coordinate')
        validate: Whether to validate the coordinate is within valid ranges

    Returns:
        A Decimal value representing degrees.
        Negative for southern or western hemisphere.

    Raises:
        ValueError: If the coordinate cannot be parsed or is outside valid range
    """
    if string is None:
        return None

    if isinstance(string, float | int | Decimal):
        decimal_result = Decimal(str(string))
        if validate:
            return _validate_coordinate(decimal_result, coord_type)
        return decimal_result

    if not isinstance(string, str):
        raise ValueError(f"Expected string, float, or Decimal, got {type(string)}")

    orig_string = string
    string = string.strip()
    if not string:
        return None

    maritime_patterns = [
        # Pattern 1: degree-dash-minutes with degree symbol: "40°–41.65'N"
        r'^(\d+\.?\d*)°[–\-](\d+\.?\d*)[\'""]?([A-Z])$',
        # Pattern 2: degree-dash-minutes without degree symbol: "54-05.48N"
        r"^(\d+\.?\d*)[–\-](\d+\.?\d*)([A-Z])$",
        # Pattern 3: degree-minutes with degree symbol: "30°34.4'N"
        r'^(\d+\.?\d*)°(\d+\.?\d*)[\'""]?([A-Z])$',
        # Pattern 4: degree-minutes-seconds: "30°34'24.0\"N"
        r'^(\d+\.?\d*)°(\d+\.?\d*)[\'""](\d+\.?\d*)[\'""]([A-Z])$',
    ]

    for pattern in maritime_patterns:
        match = re.match(pattern, string.strip())
        if match:
            groups = match.groups()

            if len(groups) == 3:  # degrees, minutes, hemisphere
                degrees = float(groups[0])
                minutes = float(groups[1])
                hemisphere = groups[2].upper()

                if hemisphere not in ("N", "S", "E", "W"):
                    raise ValueError(
                        f"Invalid hemisphere '{hemisphere}', must be N, S, E, or W"
                    )

                if degrees != int(degrees):
                    raise ValueError(
                        "Fractional degrees cannot be combined with minutes"
                    )

                if minutes >= 60:
                    raise ValueError(f"Minutes {minutes} must be less than 60")

                result = degrees + minutes / 60.0

                if hemisphere in ("S", "W"):
                    result = -result

                decimal_result = Decimal(str(result))
                if validate:
                    return _validate_coordinate(decimal_result, coord_type)
                return decimal_result

            elif len(groups) == 4:  # degrees, minutes, seconds, hemisphere
                degrees = float(groups[0])
                minutes = float(groups[1])
                seconds = float(groups[2])
                hemisphere = groups[3].upper()

                if hemisphere not in ("N", "S", "E", "W"):
                    raise ValueError(
                        f"Invalid hemisphere '{hemisphere}', must be N, S, E, or W"
                    )

                if degrees != int(degrees):
                    raise ValueError(
                        "Fractional degrees cannot be combined with minutes and seconds"
                    )

                if minutes >= 60:
                    raise ValueError(f"Minutes {minutes} must be less than 60")
                if seconds >= 60:
                    raise ValueError(f"Seconds {seconds} must be less than 60")

                result = degrees + minutes / 60.0 + seconds / 3600.0

                if hemisphere in ("S", "W"):
                    result = -result

                decimal_result = Decimal(str(result))
                if validate:
                    return _validate_coordinate(decimal_result, coord_type)
                return decimal_result

    string = string.strip().lower()

    string = string.replace("north", "n")
    string = string.replace("south", "s")
    string = string.replace("east", "e")
    string = string.replace("west", "w")

    string = string.replace("с", "n")
    string = string.replace("ю", "s")
    string = string.replace("в", "e")
    string = string.replace("з", "w")

    negative = -1 if string.endswith(("w", "s")) else 1
    negative = -1 if string.startswith(("-", "w", "s")) else negative

    try:
        parts = re.findall(r"\d+(?:[.,]\d+)?", string)
        if parts:
            parts = [float(part.replace(",", ".")) for part in parts]

            if len(parts) >= 2:  # degrees, minutes
                if parts[1] >= 60:
                    raise ValueError("Minutes must be less than 60")
            if len(parts) >= 3:  # degrees, minutes, seconds
                if parts[2] >= 60:
                    raise ValueError("Seconds must be less than 60")
                # Decimal in multiple fields is only invalid for the
                # degrees-minutes format (DMS may carry decimals on every field).
                if len(parts) == 2:
                    decimal_parts = [
                        part_str
                        for part_str in re.findall(r"\d+(?:[.,]\d+)?", orig_string)
                        if "." in part_str or "," in part_str
                    ]
                    if len(decimal_parts) > 1:
                        raise ValueError(
                            "Decimal values in multiple fields not allowed "
                            "for degrees-minutes format"
                        )

            result = math.copysign(to_dec_deg(*parts), negative)
            if not math.isfinite(result):
                raise ValueError()

            decimal_result = Decimal(str(result))
            if validate:
                return _validate_coordinate(decimal_result, coord_type)
            return decimal_result
        else:
            raise ValueError()
    except ValueError as e:
        if (
            "outside valid range" in str(e)
            or "must be less than" in str(e)
            or "Decimal values in multiple fields" in str(e)
            or "Invalid hemisphere" in str(e)
            or "Fractional degrees cannot be combined" in str(e)
        ):
            raise e
        raise ValueError(f"{orig_string!r} is not a valid coordinate string")


def _validate_coordinate(
    value: Decimal | None, coord_type: str = "coordinate"
) -> Decimal | None:
    """Validate that a coordinate is within valid ranges.

    Args:
        value: Coordinate value to validate
        coord_type: Type of coordinate ('latitude' or 'longitude' or 'coordinate')

    Returns:
        Validated coordinate or None if invalid

    Raises:
        ValueError: If coordinate is outside valid range
    """
    if value is None:
        return None

    coord_float = float(value)

    if coord_type.lower() == "latitude":
        if not (-90 <= coord_float <= 90):
            raise ValueError(f"Latitude {coord_float} is outside valid range [-90, 90]")
    elif coord_type.lower() == "longitude":
        if not (-180 <= coord_float <= 180):
            raise ValueError(
                f"Longitude {coord_float} is outside valid range [-180, 180]"
            )
    else:
        if not (-180 <= coord_float <= 180):
            raise ValueError(
                f"Coordinate {coord_float} is outside reasonable range [-180, 180]"
            )

    return value
