package cz.failxos.mwn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.failxos.mwn.data.Snapshot;
import cz.failxos.mwn.manager.GroupManager;
import cz.failxos.mwn.manager.StatsManager;
import cz.failxos.mwn.manager.WorldManager;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.Text;
import org.junit.jupiter.api.Test;

/** Tests of the pure logic that does not need a running server. */
class LogicTest {

    @Test
    void legacyColorsAreConverted() {
        assertEquals("<reset><green>Hello", Text.legacyToMini("&aHello"));
        assertEquals("<bold>Bold", Text.legacyToMini("&lBold"));
        assertEquals("<reset>", Text.legacyToMini("&r"));
    }

    @Test
    void hexColorsAreConverted() {
        assertEquals("<#B59BFF>x", Text.legacyToMini("&#B59BFFx"));
        assertEquals("<#B59BFF>", Text.legacyToMini("\u00a7x\u00a7B\u00a75\u00a79\u00a7B\u00a7F\u00a7F"));
    }

    @Test
    void unknownCodesAndMiniMessageStayUntouched() {
        assertEquals("100% safe & sound", Text.legacyToMini("100% safe & sound"));
        assertEquals("&zNo", Text.legacyToMini("&zNo"));
        assertEquals("<gradient:#fff:#000>x</gradient>", Text.legacyToMini("<gradient:#fff:#000>x</gradient>"));
        assertEquals("", Text.legacyToMini(null));
    }

    @Test
    void plainTextStripsFormatting() {
        assertEquals("Hello World", Text.plain("&aHello &lWorld"));
    }

    @Test
    void worldBaseNameStripsDimensionSuffixes() {
        assertEquals("chunkbase", WorldManager.baseName("chunkbase_nether"));
        assertEquals("chunkbase", WorldManager.baseName("chunkbase_the_end"));
        assertEquals("survival", WorldManager.baseName("survival_end"));
        assertEquals("survival", WorldManager.baseName("survival-nether"));
        assertEquals("chunk", WorldManager.baseName("chunk"));
    }

    @Test
    void worldBaseNameDoesNotMangleUnrelatedWords() {
        assertEquals("legend", WorldManager.baseName("legend"));
        assertEquals("weekend", WorldManager.baseName("weekend"));
        assertEquals("nether", WorldManager.baseName("nether"));
    }

    @Test
    void nameValidation() {
        assertTrue(GroupManager.validName("ok_name-1"));
        assertTrue(GroupManager.validName("ab"));
        assertFalse(GroupManager.validName("a"));
        assertFalse(GroupManager.validName("bad name"));
        assertFalse(GroupManager.validName("a".repeat(33)));
        assertFalse(GroupManager.validName(null));
        assertTrue(WorldManager.validWorldName("ChunkBase_Nether"));
        assertFalse(WorldManager.validWorldName("../etc"));
    }

    @Test
    void playtimeFormat() {
        assertEquals("1h 2m", StatsManager.format(3725));
        assertEquals("0h 0m", StatsManager.format(0));
    }

    @Test
    void dataProfilesDecideWhatIsSharedBetweenGroups() {
        Group a = new Group("server_001", "a");
        Group b = new Group("server_002", "b");
        // both isolated -> different profiles -> data is swapped on switch
        assertEquals("server_001", Snapshot.profile(a, Snapshot.INVENTORY));
        assertFalse(Snapshot.profile(a, Snapshot.INVENTORY).equals(Snapshot.profile(b, Snapshot.INVENTORY)));
        // shared -> equal profile -> nothing is saved/loaded, so nothing can be duplicated
        a.separateInventory = false;
        b.separateInventory = false;
        assertEquals(Snapshot.profile(a, Snapshot.INVENTORY), Snapshot.profile(b, Snapshot.INVENTORY));
        assertEquals("shared", Snapshot.profile(a, Snapshot.INVENTORY));
        // location is always per group; ungrouped players use the shared pool
        assertEquals("server_001", Snapshot.profile(a, Snapshot.LOCATION));
        assertEquals("shared", Snapshot.profile(null, Snapshot.LOCATION));
    }

    @Test
    void groupFeatureSwitchesAreCaseInsensitiveAndDefaultOn() {
        Group g = new Group("server_001", "a");
        assertTrue(g.feature("Shop"));
        g.features.put("Shop", false);
        assertFalse(g.feature("shop"));
        assertTrue(g.feature("Jobs"));
        assertTrue(g.rule("pvp"));
        g.rules.put("pvp", false);
        assertFalse(g.rule("pvp"));
    }
}
