package ca.kijiji.contest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.kijiji.contest.ticketworkers.StreetFineTabulator;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang.StringUtils;
import org.slf4j.*;

/**
 * Resolves addresses to street names
 * Uses thread-safe caching internally.
 */
public class StreetNameResolver {

    private static final Logger LOG = LoggerFactory.getLogger(StreetFineTabulator.class);

    // Regex to separate the street number from the street name
    // there need not be a street number, but it must be a combination of digits and punctuation with
    // an optional letter at the end for apartments. (ex. "123/345", "12451&2412", "2412a", "33-44", "235-a", "22, 77b")
    // Also handles junk street numbers (like 222-, -33, !33, 1o2, l22) because they aren't important
    // OCR must have been used for some of the data entry o's and l's are mixed with 0s and 1s. We consider lower-case
    // letters to be part of the street number since that's the only place they show up. Street names are all upper-case.
    // Whoever released this dataset as-is is a sadist.
    private static final String STREET_NUM_REGEX = "(?<num>[\\p{N}\\p{Ll}\\-&/, ]*)";
    private static final String STREET_NAME_REGEX = "(?<street>[\\p{N}\\p{L} \\.'-]*)";

    //Ignore garbage at the beginning and end of the string and pull out the street numbers / names
    private static final Pattern ADDR_REGEX =
            Pattern.compile("^[^\\p{N}\\p{L}]*(" + STREET_NUM_REGEX + "\\s+)?" + STREET_NAME_REGEX + ".*");

    // Set of directions a street may end with
    private static final ImmutableSet<String> DIRECTION_SET = ImmutableSet.of(
            //"NS" means either North *or* South? Only shows up in a couple of places
            "N", "NORTH", "S", "SOUTH", "W", "WEST", "E", "EAST", "NE", "NW", "SW", "SE", "NS"
    );

    // Set of designators to remove from the end of street names (ST, ROAD, etc.)
    // The designation may be necessary for disambiguation (YONGE BLVD vs YONGE ST,) so it'd be *better*
    // to normalize the designation, but the test case requires no designations.
    private static final ImmutableSet<String> DESIGNATION_SET = ImmutableSet.of(
            // mostly from the top of
            // `cut -d, -f8 Parking_Tags_Data_2012.csv | sed 's/\s+$//g' | awk -F' ' '{print $NF}' | sort | uniq -c | sort -n`
            "AV", "AVE", "AVENUE", "BL", "BLV", "BLVD", "BOULEVARD", "CIR", "CIRCLE", "CR", "CRCL", "CRCT", "CRES", "CRS",
            "CRST", "CRESCENT", "CT", "CRT", "COURT", "D", "DR", "DRIVE", "GATE", "GARDEN", "GDN", "GDNS", "GARDENS", "GR", "GRDNS",
            "GROVE", "GRV", "GT", "HGHTS", "HEIGHTS", "HTS", "HILL", "LN", "LANE", "MANOR", "MEWS", "PARKWAY", "PK", "PKWY",
            "PRK", "PL", "PLCE", "PLACE", "PROMENADE", "QUAY", "RD", "ROAD", "ST", "STR", "SQ", "SQUARE", "STREET", "T", "TER",
            "TERR", "TERRACE", "TR", "TRL", "TRAIL", "VISTA", "V", "WAY", "WY", "WOOD"

    );

    // Map of cache-friendly addresses to their respective street names
    private final ConcurrentHashMap<String, String> _mStreetCache = new ConcurrentHashMap<>();

    public StreetNameResolver() {

    }

    /**
     * Get a street name (ex: FAKE) from an address (ex: 123 FAKE ST W)
     */
    public String addrToStreetName(String addr) {

        // Try to remove the street number from the front so we're more likely to get a cache hit
        String streetCacheKey = _getCacheableAddress(addr);

        // We have a valid cache key, check if we have a cached name
        String streetName = _mStreetCache.get(streetCacheKey);

        // No normalized version in the cache, calculate it
        if(streetName == null) {

            // Split the address into street number and street components
            Matcher addrMatches = ADDR_REGEX.matcher(addr);

            // Hmmm, this doesn't really look like an address, bail out.
            if(!addrMatches.matches()) {
                return null;
            }

            // Get just the street *name* from the street
            streetName = _isolateStreetName(addrMatches.group("street"));

            // Add the street name to the cache. We don't really care if this gets clobbered,
            // we put in the same val for a key no matter what.
            _mStreetCache.put(streetCacheKey, streetName);
        }

        return streetName;
    }

    /**
     * Get a cacheable version of this address for street name lookups by lopping off the
     * Street number (if we can.)
     * Results in a 17% speed increase over always running the regex and using group("street").
     *
     * This optimizes for the common case of NUMBER STREET DESIGNATION? DIRECTION?
     */
    private static String _getCacheableAddress(String addr) {
        // Regex matches are expensive! Try to get a cache hit without one.
        // Remove the street number from the start of the address if there is one.

        // charAt() probably doesn't work right with surrogate pairs.
        // Luckily, we don't need to support hieroglyphs.
        if(Character.isDigit(addr.charAt(0))) {

            // Check where the first space is
            int space_idx = addr.indexOf(' ');

            if(space_idx != -1) {

                // A street number may end in a lowercase letter but never in an uppercase letter.
                // all street names use uppercase letters.
                if (Character.isUpperCase(addr.charAt(space_idx - 1))) {
                    // This is probably a street name, return as-is.
                    return addr;
                }

                // Lop off (what I hope is) the street number and return the rest
                return addr.substring(space_idx);
            }
        }

        //Doesn't start with a digit, probably starts with a street name
        return addr;
    }

    /**
     * Get *just* the street name from a street
     * */
    private String _isolateStreetName(String street) {

        // Split the street up into tokens
        String[] streetToks = StringUtils.split(street, ' ');

        // Go backwards through the tokens and skip all the ones that aren't likely part of the actual name.
        int lastNameElem = 0;

        for(int i = streetToks.length - 1; i > -1; --i) {
            String tok = streetToks[i];

            lastNameElem = i;

            // There may be multiple direction tokens (N E, S E, etc.) but they never show up before a
            // street designation. Stop looking at tokens as soon as we hit the first token that looks
            // like a street designation, otherwise we'll mangle names like "HILL STREET"
            // streets like "GROVE" with no designator will get mangled, but junk in junk out.
            if(DESIGNATION_SET.contains(tok)) {
                break;
            }
            // This token is neither a direction nor a designation, this is part of the street name!
            // Bail out.
            else if(!DIRECTION_SET.contains(tok)) {
                // join's range is non-inclusive, increment it so this token is included in the street name
                ++lastNameElem;
                break;
            }
        }

        // join together the tokens that make up the street's name and return
        return StringUtils.join(streetToks, ' ', 0, lastNameElem);
    }
}