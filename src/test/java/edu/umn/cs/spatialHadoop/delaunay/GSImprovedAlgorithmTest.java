package edu.umn.cs.spatialHadoop.delaunay;

import edu.umn.cs.spatialHadoop.core.Point;
import edu.umn.cs.spatialHadoop.core.SpatialAlgorithms;
import edu.umn.cs.spatialHadoop.operations.Head;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.hadoop.io.BooleanWritable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Unit test for the utility class {@link Head}.
 */
public class GSImprovedAlgorithmTest extends TestCase {

  /**
   * Create the test case
   *
   * @param testName
   *          name of the test case
   */
  public GSImprovedAlgorithmTest(String testName) {
    super(testName);
  }

  /**
   * @return the suite of tests being tested
   */
  public static Test suite() {
    return new TestSuite(GSImprovedAlgorithmTest.class);
  }

  /**
   * Test Delaunay Triangulation for some small test datasets.
   */
  public void testTriangulations() {
    String[] datasetNames = {"test_dt1", "test_dt1", "test_dt3", "test_dt4", "test_dt5", "test_dt6", "test_dt7", "test_dt8"};
    try {
      for (String datasetName : datasetNames) {
        System.out.println("Testing "+datasetName);
        Point[] points = GSDTAlgorithmTest.readPoints("src/test/resources/"+datasetName+".points");
        List<Point[]> correctTriangulation = GSDTAlgorithmTest.readTriangles("src/test/resources/"+datasetName+".triangles", points);

        GSImprovedAlgorithm algo = new GSImprovedAlgorithm(points, null);
        Triangulation answer = algo.getFinalTriangulation();

        for (Point[] triangle : answer.iterateTriangles()) {
          boolean found = false;
          int i = 0;
          while (!found && i < correctTriangulation.size()) {
            found = GSDTAlgorithmTest.arrayEqualAnyOrder(triangle, correctTriangulation.get(i));
            if (found)
              correctTriangulation.remove(i);
            else
              i++;
          }
          assertTrue(String.format("Triangle (%d, %d, %d) not expected in the answer",
              Arrays.binarySearch(points, triangle[0]),
              Arrays.binarySearch(points, triangle[1]),
              Arrays.binarySearch(points, triangle[2])), found);
        }
        for (Point[] triangle : correctTriangulation) {
          System.out.printf("Triangle (%d, %d, %d) expected but not found\n",
              Arrays.binarySearch(points, triangle[0]),
              Arrays.binarySearch(points, triangle[1]),
              Arrays.binarySearch(points, triangle[2]));
        }
        assertTrue(String.format("%d triangles not found", correctTriangulation.size()),
            correctTriangulation.isEmpty());
      }
    } catch (IOException e) {
      fail("File not found");
    }
  }

  /**
   * Put the algorithm under stress test by generating many random test points
   * and checking that the output of every merge step is a correct Delaunay
   * triangulation. If an error happens, it is reported as early as possible to
   * make it easier to further investigate the error.
   */
  public void testRandomPoints() {
    // Skip the stress test unless the environment variable "stresstest" is set
    if (System.getenv("stresstest") == null)
      return;
    int numIterations = 100;
    Random random = new Random(1);
    // A flag that is set to true when the first error is found
    final BooleanWritable errorFound = new BooleanWritable(false);
    for (int i = 0; i < numIterations; i++) {
      System.out.printf("Stress test iteration #%d\n", i);
      int numPoints = random.nextInt(500) + 500; // random [500, 1000)
      Point[] points = new Point[numPoints];
      for (int j = 0; j < points.length; j++)
        points[j] = new Point(random.nextInt(1000), random.nextInt(1000));
      // Remove duplicates which are not allowed in Delaunay triangulation
      points = SpatialAlgorithms.deduplicatePoints(points, 1E-10f);

      GSImprovedAlgorithm algo = new GSImprovedAlgorithm(points, null) {
        @Override
        protected IntermediateTriangulation merge(IntermediateTriangulation L, IntermediateTriangulation R) {
          IntermediateTriangulation partialAnswer = super.merge(L, R);
          if (!errorFound.get()) {
            // No error found so far, test for errors
            if (partialAnswer.isIncorrect()) {
              System.out.println("Found an error");
              errorFound.set(true);
            }
          }
          return partialAnswer;
        }
      };

      assertTrue(String.format("Found an error in iteration #%d", i), !errorFound.get());
    }
  }

}
