package eu.stratosphere.sopremo.cleansing.similarity.contentbased;

import java.util.Arrays;
import java.util.List;

import org.junit.Ignore;
import org.junit.runners.Parameterized.Parameters;

import eu.stratosphere.sopremo.cleansing.similarity.Similarity;
import eu.stratosphere.sopremo.cleansing.similarity.SimilarityBaseTest;
import eu.stratosphere.sopremo.cleansing.similarity.set.JaccardSimilarity;

/**
 * Tests the {@link JaccardSimilaritySimilarity} class.
 * 
 * @author Arvid Heise
 */
@Ignore
public class JaccardSimilaritySimilarityTest extends SimilarityBaseTest {
	public JaccardSimilaritySimilarityTest(Object node1, Object node2, double expected) {
		super(node1, node2, expected);
	}

	/* (non-Javadoc)
	 * @see de.hpi.fgis.dude.junit.Similarity.SimilarityBaseTest#getSimilarity()
	 */
	@Override
	public Similarity<?> getSimilarity() {
		return new JaccardSimilarity();
	}
	
	@Parameters
	public static  List<Object[]>  getParameters() {
		return Arrays.asList(new Object[][] {
			{ "thomas", "thomas", 1 },
			{ "hans-peter", "hans-peter", 1 },
			{ "hans-peter", "Hans-peter", 0 },
			{ "thomas", "", 0 },
			{ "thomas", null, withCoercion(0) },
			{ "", "", 1 },
			{ "", null, withCoercion(1) },
		});
	}
}
