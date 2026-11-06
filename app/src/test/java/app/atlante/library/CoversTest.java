package app.atlante.library;

import static org.junit.Assert.*;
import org.junit.Test;

public class CoversTest {
    @Test public void thumbnailsShrinkByPowersOfTwoWithoutGoingBelowTheCard() {
        assertEquals(1, Covers.sampleSize(400, 600, 500));    // already close to card size
        assertEquals(2, Covers.sampleSize(1200, 1800, 700));  // 600x900: still taller than the card
        assertEquals(4, Covers.sampleSize(2400, 3600, 700));  // 600x900
        assertEquals(1, Covers.sampleSize(100, 150, 700));    // small images are never enlarged
    }
}
