package org.dependencytrack.search;

import alpine.Config;
import org.apache.commons.io.FileUtils;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.VulnerableSoftware;
import org.dependencytrack.persistence.QueryManager;
import org.dependencytrack.search.document.VulnerableSoftwareDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import us.springett.parsers.cpe.Cpe;
import us.springett.parsers.cpe.CpeParser;
import us.springett.parsers.cpe.exceptions.CpeEncodingException;
import us.springett.parsers.cpe.exceptions.CpeParsingException;
import us.springett.parsers.cpe.exceptions.CpeValidationException;
import us.springett.parsers.cpe.values.Part;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FuzzyVulnerableSoftwareSearchManagerTest {
    private static final File INDEX_DIRECTORY;
    private static final File INDEX_TEMP_DIRECTORY;
    private FuzzyVulnerableSoftwareSearchManager toTest = new FuzzyVulnerableSoftwareSearchManager(true);
    private QueryManager qm;
    private final VulnerableSoftware VALUE_TO_MATCH = new VulnerableSoftware();
    static {
        INDEX_DIRECTORY = new File(
                Config.getInstance().getDataDirectorty(),
                "index" + File.separator + IndexManager.IndexType.VULNERABLESOFTWARE.name().toLowerCase());
        INDEX_TEMP_DIRECTORY = new File(INDEX_DIRECTORY.getAbsolutePath() + "_tmp");
    }

    @BeforeAll
    public static void saveVsIndex() throws IOException {
        VulnerableSoftwareIndexer.getInstance().close();
        if (INDEX_TEMP_DIRECTORY.exists()) {
            FileUtils.deleteDirectory(INDEX_TEMP_DIRECTORY);
        }
        if (INDEX_DIRECTORY.exists()) {
            INDEX_DIRECTORY.renameTo(INDEX_TEMP_DIRECTORY);
        }
        VulnerableSoftware vs = new VulnerableSoftware();
        vs.setUuid(UUID.randomUUID());
        vs.setCpe23("cpe:2.3:a:libexpat_project:libexpat:2.2.2:*:*:*:*:*:*:*");
        vs.setProduct("libexpat");
        VulnerableSoftwareIndexer.getInstance().add(new VulnerableSoftwareDocument(vs));
        VulnerableSoftwareIndexer.getInstance().commit();
    }
    @AfterAll
    public static void restoreVsIndex() throws IOException {
        VulnerableSoftwareIndexer.getInstance().close();
        if (INDEX_DIRECTORY.exists()) {
            FileUtils.deleteDirectory(INDEX_DIRECTORY);
        }
        if (INDEX_TEMP_DIRECTORY.exists()) {
            Assertions.assertTrue(INDEX_TEMP_DIRECTORY.renameTo(INDEX_DIRECTORY));
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        qm = mock(QueryManager.class);
        when(qm.getObjectByUuid(any(), anyString())).thenReturn(VALUE_TO_MATCH);
    }

    @Test
    void fuzzyAnalysis() throws CpeParsingException, CpeValidationException {
        us.springett.parsers.cpe.Cpe justThePart = new us.springett.parsers.cpe.Cpe(Part.APPLICATION, "*", "*", "*", "*", "*", "*", "*", "*", "*", "*");
        // wildcard all components after part to constrain fuzzing to components of same type e.g. application, operating-system
        String fuzzyTerm = FuzzyVulnerableSoftwareSearchManager.getLuceneCpeRegexp(justThePart.toCpe23FS());
        SearchResult searchResult = toTest.searchIndex("product:libexpat1~0.88 AND " + fuzzyTerm);
        // Oddly validating lucene first cuz can't decouple from that.
        Assertions.assertEquals(1, searchResult.getResults().size());
        Assertions.assertEquals(1, searchResult.getResults().values().iterator().next().size());

        Component component = new Component();
        component.setName("libexpat1");
        component.setCpe("cpe:2.3:a:libexpat_project:libexpat1:2.0.0:*:*:*:*:*:*:*");
        Cpe cpe = CpeParser.parse(component.getCpe());
        List<VulnerableSoftware> vs = toTest.fuzzyAnalysis(qm, component, cpe);
        Assertions.assertFalse(vs.isEmpty());
        Assertions.assertSame(VALUE_TO_MATCH, vs.get(0));

    }

    @Test
    void getLuceneCpeRegexp() throws CpeValidationException, CpeEncodingException {
        us.springett.parsers.cpe.Cpe os = new us.springett.parsers.cpe.Cpe( Part.OPERATING_SYSTEM, "vendor", "product", "1\\.0", "2", "33","en", "inside", "Vista", "x86", "other");

        Assertions.assertEquals("cpe23:/cpe\\:2\\.3\\:a\\:.*\\:.*\\:.*\\:.*\\:.*\\:.*\\:.*\\:.*\\:.*\\:.*/", FuzzyVulnerableSoftwareSearchManager.getLuceneCpeRegexp("cpe:2.3:a:*:*:*:*:*:*:*:*:*:*"));
        Assertions.assertEquals("cpe23:/cpe\\:2\\.3\\:o\\:vendor\\:product\\:1.0\\:2\\:33\\:en\\:inside\\:Vista\\:x86\\:other/", FuzzyVulnerableSoftwareSearchManager.getLuceneCpeRegexp(os.toCpe23FS()));
        Assertions.assertEquals("cpe22:/cpe\\:\\/o\\:vendor\\:product\\:1.0\\:2\\:33\\:en/", FuzzyVulnerableSoftwareSearchManager.getLuceneCpeRegexp(os.toCpe22Uri()));
    }

    @Test
    @Disabled("This demonstrates assumptions about CPE matching but does not exercise code")
    void cpeMatching() {
        String lucene = FuzzyVulnerableSoftwareSearchManager.getLuceneCpeRegexp("cpe:2.3:a:*:file:*:*:*:*:*:*:*:*");
        String regex = lucene.substring(7, lucene.length()-1);
        Pattern pattern = Pattern.compile(regex);
        Assertions.assertFalse(pattern.matcher(
        "cpe:2.3:a:dell:emc_vnx2_operating_environment:*:*:*:*:*:file:*:*").matches());
        Assertions.assertTrue(pattern.matcher(
                "cpe:2.3:a:*:file:*:*:*:*:*:file:*:*").matches());
    }
}