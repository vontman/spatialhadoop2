/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/
package edu.umn.cs.spatialHadoop.indexing;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.LocalJobRunner;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.LineReader;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.Point;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.ResultCollector;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.indexing.IndexOutputFormat.IndexRecordWriter;
import edu.umn.cs.spatialHadoop.io.Text2;
import edu.umn.cs.spatialHadoop.mapreduce.RTreeRecordReader3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialInputFormat3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialRecordReader3;
import edu.umn.cs.spatialHadoop.nasa.HDFRecordReader;
import edu.umn.cs.spatialHadoop.operations.FileMBR;
import edu.umn.cs.spatialHadoop.operations.Sampler;
import edu.umn.cs.spatialHadoop.util.FileUtil;

/**
 * @author Ahmed Eldawy
 *
 */
public class Indexer {
  private static final Log LOG = LogFactory.getLog(Indexer.class);
  
  /**
   * The map function that partitions the data using the configured partitioner
   * @author Eldawy
   *
   */
  public static class PartitionerMap extends
    Mapper<Rectangle, Iterable<? extends Shape>, IntWritable, Shape> {

    /**The partitioner used to partitioner the data across reducers*/
    private Partitioner partitioner;
    /**
     * Whether to replicate a record to all overlapping partitions or to assign
     * it to only one partition
     */
    private boolean replicate;
    
    @Override
    protected void setup(Context context)
        throws IOException, InterruptedException {
      super.setup(context);
      this.partitioner = Partitioner.getPartitioner(context.getConfiguration());
      this.replicate = context.getConfiguration().getBoolean("replicate", false);
    }
    
    @Override
    protected void map(Rectangle key, Iterable<? extends Shape> shapes,
        final Context context) throws IOException,
        InterruptedException {
      final IntWritable partitionID = new IntWritable();
      for (final Shape shape : shapes) {
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR == null)
          continue;
        if (replicate) {
          partitioner.overlapPartitions(shape, new ResultCollector<Integer>() {
            @Override
            public void collect(Integer r) {
              partitionID.set(r);
              try {
                context.write(partitionID, shape);
              } catch (IOException e) {
                LOG.warn("Error checking overlapping partitions", e);
              } catch (InterruptedException e) {
                LOG.warn("Error checking overlapping partitions", e);
              }
            }
          });
        } else {
          partitionID.set(partitioner.overlapPartition(shape));
          if (partitionID.get() >= 0)
            context.write(partitionID, shape);
        }
        context.progress();
      }
    }
  }

  
  public static class PartitionerReduce<S extends Shape>
    extends Reducer<IntWritable, Shape, IntWritable, Shape> {

    @Override
    protected void reduce(IntWritable partitionID, Iterable<Shape> shapes,
        Context context) throws IOException, InterruptedException {
      LOG.info("Working on partition #"+partitionID);
      for (Shape shape : shapes) {
        context.write(partitionID, shape);
        context.progress();
      }
      // Indicate end of partition to close the file
      context.write(new IntWritable(-partitionID.get()-1), null);
      LOG.info("Done with partition #"+partitionID);
    }
  }
    
  private static Job indexMapReduce(Path inPath, Path outPath,
      OperationsParams paramss) throws IOException, InterruptedException,
      ClassNotFoundException {
    Job job = new Job(paramss, "Indexer");
    Configuration conf = job.getConfiguration();
    job.setJarByClass(Indexer.class);
    
    // Set input file MBR if not already set
    Rectangle inputMBR = (Rectangle) OperationsParams.getShape(conf, "mbr");
    if (inputMBR == null) {
      inputMBR = FileMBR.fileMBR(inPath, new OperationsParams(conf));
      OperationsParams.setShape(conf, "mbr", inputMBR);
    }
    
    // Set the correct partitioner according to index type
    String sindex = conf.get("sindex");
    SpatialSite.SpatialIndex spatialIndex;
    if (sindex != null && SpatialSite.CommonSpatialIndex.containsKey(sindex))
      spatialIndex = SpatialSite.CommonSpatialIndex.get(sindex);
    else
      spatialIndex = new SpatialSite.SpatialIndex();
    if (conf.get("gindex") != null)
      spatialIndex.gindex = SpatialSite.getGlobalIndex(conf.get("gindex"));
    if (conf.get("lindex") != null)
      spatialIndex.lindex = SpatialSite.getLocalIndex(conf.get("lindex"));
    spatialIndex.disjoint = conf.getBoolean("disjoint", spatialIndex.disjoint);

    long t1 = System.currentTimeMillis();
    if (spatialIndex.lindex != null)
      conf.setClass(LocalIndex.LocalIndexClass, spatialIndex.lindex, LocalIndex.class);
    Partitioner partitioner = initializeGlobalIndex(inPath, outPath, conf, spatialIndex.gindex);
    Partitioner.setPartitioner(conf, partitioner);
    
    long t2 = System.currentTimeMillis();
    System.out.println("Total time for space subdivision in millis: "+(t2-t1));
    
    // Set mapper and reducer
    Shape shape = OperationsParams.getShape(conf, "shape");
    job.setMapperClass(PartitionerMap.class);
    job.setMapOutputKeyClass(IntWritable.class);
    job.setMapOutputValueClass(shape.getClass());
    job.setReducerClass(PartitionerReduce.class);
    // Set input and output
    job.setInputFormatClass(SpatialInputFormat3.class);
    SpatialInputFormat3.setInputPaths(job, inPath);
    job.setOutputFormatClass(IndexOutputFormat.class);
    IndexOutputFormat.setOutputPath(job, outPath);
    // Set number of reduce tasks according to cluster status
    ClusterStatus clusterStatus = new JobClient(new JobConf()).getClusterStatus();
    job.setNumReduceTasks(Math.max(1, Math.min(partitioner.getPartitionCount(),
        (clusterStatus.getMaxReduceTasks() * 9) / 10)));

    // Use multithreading in case the job is running locally
    conf.setInt(LocalJobRunner.LOCAL_MAX_MAPS, Runtime.getRuntime().availableProcessors());
    
    // Start the job
    if (conf.getBoolean("background", false)) {
      // Run in background
      job.submit();
    } else {
      job.waitForCompletion(conf.getBoolean("verbose", false));
    }
    return job;
  }

  public static Partitioner initializeGlobalIndex(Path in, Path out,
      Configuration job, Class<? extends Partitioner> gindex) throws IOException {
    return initializeGlobalIndex(new Path[] {in}, out, job, gindex);
  }

  /**
   * Create a partitioner for a particular job
   * @param ins
   * @param out
   * @param job
   * @param partitionerClass
   * @return
   * @throws IOException
   */
  public static Partitioner initializeGlobalIndex(Path[] ins, Path out,
      Configuration job, Class<? extends Partitioner> partitionerClass) throws IOException {

    // Determine number of partitions
    long inSize = 0;
    for (Path in : ins) {
      inSize += FileUtil.getPathSize(in.getFileSystem(job), in);
    }
    long estimatedOutSize = (long) (inSize * (1.0 + job.getFloat(SpatialSite.INDEXING_OVERHEAD, 0.1f)));
    FileSystem outFS = out.getFileSystem(job);
    long outBlockSize = outFS.getDefaultBlockSize(out);

    try {
      Partitioner partitioner;

      Partitioner.GlobalIndexerMetadata partitionerMetadata = partitionerClass.getAnnotation(Partitioner.GlobalIndexerMetadata.class);
      boolean disjointSupported = partitionerMetadata != null && partitionerMetadata.disjoint();

      if (job.getBoolean("disjoint", false) && !disjointSupported)
        throw new RuntimeException("Partitioner " + partitionerClass.getName() + " does not support disjoint partitioning");

      try {
        Constructor<? extends Partitioner> c = partitionerClass.getConstructor(Rectangle.class, int.class);
        // Constructor needs an MBR and number of partitions
        final Rectangle inMBR = (Rectangle) OperationsParams.getShape(job, "mbr");
        int numOfPartitions = (int) Math.ceil((double)estimatedOutSize / outBlockSize);
        return (Partitioner) c.newInstance(inMBR, numOfPartitions);
      } catch (NoSuchMethodException e) {
        try {
          Constructor<? extends Partitioner> c = partitionerClass.getConstructor(Point[].class, int.class);
          // Constructor needs a sample and capacity (no MBR)
          final List<Point> sample = readSample(ins, job);
          Point[] sampleArray = sample.toArray(new Point[sample.size()]);
          int partitionCapacity = (int) Math.max(1, Math.floor((double)sample.size() * outBlockSize / estimatedOutSize));
          return (Partitioner) c.newInstance(sampleArray, partitionCapacity);
        } catch (NoSuchMethodException e1) {
          try {
            Constructor<? extends Partitioner> c = partitionerClass.getConstructor(Rectangle.class, Point[].class, int.class);
            final Rectangle inMBR = (Rectangle) OperationsParams.getShape(job, "mbr");
            final List<Point> sample = readSample(ins, job);
            Point[] sampleArray = sample.toArray(new Point[sample.size()]);
            int partitionCapacity = (int) Math.max(1, Math.floor((double)sample.size() * outBlockSize / estimatedOutSize));
            return (Partitioner) c.newInstance(inMBR, sampleArray, partitionCapacity);
          } catch (NoSuchMethodException e2) {
            throw new RuntimeException("Could not find a suitable constructor for the partitioner "+partitionerClass.getName());
          }
        }
      }
    } catch (InstantiationException e) {
      e.printStackTrace();
      return null;
    } catch (IllegalAccessException e) {
      e.printStackTrace();
      return null;
    } catch (InvocationTargetException e) {
      e.printStackTrace();
      return null;
    }
  }

  private static List<Point> readSample(Path[] ins, Configuration job) throws IOException {
    long t1 = System.currentTimeMillis();

    final List<Point> sample = new ArrayList<Point>();
    float sample_ratio = job.getFloat(SpatialSite.SAMPLE_RATIO, 0.01f);
    long sample_size = job.getLong(SpatialSite.SAMPLE_SIZE, 100 * 1024 * 1024);

    LOG.info("Reading a sample of "+(int)Math.round(sample_ratio*100) + "%");
    ResultCollector<Point> resultCollector = new ResultCollector<Point>(){
      @Override
      public void collect(Point p) {
        sample.add(p.clone());
      }
    };

    OperationsParams params2 = new OperationsParams(job);
    params2.setFloat("ratio", sample_ratio);
    params2.setLong("size", sample_size);
    if (job.get("shape") != null)
    params2.set("shape", job.get("shape"));
    if (job.get("local") != null)
    params2.set("local", job.get("local"));
    params2.setClass("outshape", Point.class, Shape.class);
    Sampler.sample(ins, resultCollector, params2);
    long t2 = System.currentTimeMillis();
    System.out.println("Total time for sampling in millis: "+(t2-t1));
    LOG.info("Finished reading a sample of "+sample.size()+" records");
    return sample;
  }

  private static void indexLocal(Path inPath, final Path outPath,
      OperationsParams params) throws IOException, InterruptedException {
    Job job = Job.getInstance(params);
    final Configuration conf = job.getConfiguration();

    String sindex = conf.get("sindex");
    SpatialSite.SpatialIndex spatialIndex = sindex == null ?
        new SpatialSite.SpatialIndex() :
        SpatialSite.CommonSpatialIndex.get(sindex);
    if (conf.get("gindex") != null)
      spatialIndex.gindex = SpatialSite.getGlobalIndex(conf.get("gindex"));
    if (conf.get("lindex") != null)
      spatialIndex.lindex = SpatialSite.getLocalIndex(conf.get("lindex"));
    spatialIndex.disjoint = conf.getBoolean("disjoint", spatialIndex.disjoint);
    
    // Start reading input file
    List<InputSplit> splits = new ArrayList<InputSplit>();
    final SpatialInputFormat3<Rectangle, Shape> inputFormat = new SpatialInputFormat3<Rectangle, Shape>();
    FileSystem inFs = inPath.getFileSystem(conf);
    FileStatus inFStatus = inFs.getFileStatus(inPath);
    if (inFStatus != null && !inFStatus.isDir()) {
      // One file, retrieve it immediately.
      // This is useful if the input is a hidden file which is automatically
      // skipped by FileInputFormat. We need to plot a hidden file for the case
      // of plotting partition boundaries of a spatial index
      splits.add(new FileSplit(inPath, 0, inFStatus.getLen(), new String[0]));
    } else {
      SpatialInputFormat3.setInputPaths(job, inPath);
      for (InputSplit s : inputFormat.getSplits(job))
        splits.add(s);
    }
    
    // Copy splits to a final array to be used in parallel
    final FileSplit[] fsplits = splits.toArray(new FileSplit[splits.size()]);

    // Set input file MBR if not already set
    Rectangle inputMBR = (Rectangle) OperationsParams.getShape(conf, "mbr");
    if (inputMBR == null) {
      inputMBR = FileMBR.fileMBR(inPath, new OperationsParams(conf));
      OperationsParams.setShape(conf, "mbr", inputMBR);
    }

    if (spatialIndex.lindex != null)
      conf.setClass(LocalIndex.LocalIndexClass, spatialIndex.lindex, LocalIndex.class);
    final Partitioner partitioner = initializeGlobalIndex(inPath, outPath, conf, spatialIndex.gindex);

    final IndexRecordWriter<Shape> recordWriter = new IndexRecordWriter<Shape>(
        partitioner, spatialIndex.disjoint, sindex, outPath, conf);
    for (FileSplit fsplit : fsplits) {
      RecordReader<Rectangle, Iterable<Shape>> reader = inputFormat.createRecordReader(fsplit, null);
      if (reader instanceof SpatialRecordReader3) {
        ((SpatialRecordReader3)reader).initialize(fsplit, conf);
      } else if (reader instanceof RTreeRecordReader3) {
        ((RTreeRecordReader3)reader).initialize(fsplit, conf);
      } else if (reader instanceof HDFRecordReader) {
        ((HDFRecordReader)reader).initialize(fsplit, conf);
      } else {
        throw new RuntimeException("Unknown record reader");
      }

      final IntWritable partitionID = new IntWritable();

      while (reader.nextKeyValue()) {
        Iterable<Shape> shapes = reader.getCurrentValue();
        if (spatialIndex.disjoint) {
          for (final Shape s : shapes) {
            if (s == null)
              continue;
            Rectangle mbr = s.getMBR();
            if (mbr == null)
              continue;
            partitioner.overlapPartitions(mbr, new ResultCollector<Integer>() {
              @Override
              public void collect(Integer id) {
                partitionID.set(id);
                try {
                  recordWriter.write(partitionID, s);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }
            });
          }
        } else {
          for (final Shape s : shapes) {
            if (s == null)
              continue;
            Rectangle mbr = s.getMBR();
            if (mbr == null)
              continue;
            int pid = partitioner.overlapPartition(mbr);
            if (pid != -1) {
              partitionID.set(pid);
              recordWriter.write(partitionID, s);
            }
          }
        }
      }
      reader.close();
    }
    recordWriter.close(null);
    
    // Write the WKT formatted master file
    Path masterPath = new Path(outPath, "_master." + sindex);
    FileSystem outFs = outPath.getFileSystem(params);
    Path wktPath = new Path(outPath, "_"+sindex+".wkt");
    PrintStream wktOut = new PrintStream(outFs.create(wktPath));
    wktOut.println("ID\tBoundaries\tRecord Count\tSize\tFile name");
    Text tempLine = new Text2();
    Partition tempPartition = new Partition();
    LineReader in = new LineReader(outFs.open(masterPath));
    while (in.readLine(tempLine) > 0) {
      tempPartition.fromText(tempLine);
      wktOut.println(tempPartition.toWKT());
    }
    in.close();
    wktOut.close();
  }
  
  public static Job index(Path inPath, Path outPath, OperationsParams params)
      throws IOException, InterruptedException, ClassNotFoundException {
    if (OperationsParams.isLocal(new JobConf(params), inPath)) {
      indexLocal(inPath, outPath, params);
      return null;
    } else {
      Job job = indexMapReduce(inPath, outPath, params);
      if (!job.isSuccessful())
        throw new RuntimeException("Failed job "+job);
      return job;
    }
  }

  protected static void printUsage() {
    System.out.println("Builds a spatial index on an input file");
    System.out.println("Parameters (* marks required parameters):");
    System.out.println("<input file> - (*) Path to input file");
    System.out.println("<output file> - (*) Path to output file");
    System.out.println("shape:<point|rectangle|polygon> - (*) Type of shapes stored in input file");
    System.out.println("sindex:<index> - Type of spatial index (grid|str|str+|rtree|r+tree|quadtree|zcurve|hilbert|kdtree)");
    System.out.println("gindex:<index> - Type of the global index (grid|str|rstree|kdtree|zcurve|hilbert|quadtree)");
    System.out.println("lindex:<index> - Type of the local index (rrstree)");
    System.out.println("-overwrite - Overwrite output file without notice");
    GenericOptionsParser.printGenericCommandUsage(System.out);
  }

  /**
   * Entry point to the indexing operation.
   * @param args
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {
    OperationsParams params = new OperationsParams(new GenericOptionsParser(args));
    
    if (!params.checkInputOutput(true)) {
      printUsage();
      return;
    }
    Path inputPath = params.getInputPath();
    Path outputPath = params.getOutputPath();

    // The spatial index to use
    long t1 = System.currentTimeMillis();
    index(inputPath, outputPath, params);
    long t2 = System.currentTimeMillis();
    System.out.println("Total indexing time in millis "+(t2-t1));
  }

}
