package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import mpicbg.models.AffineModel2D;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.CommandLineParameters;
import org.janelia.render.client.parameters.MatchDataClientParameters;
import org.janelia.render.client.parameters.MatchDerivationParameters;
import org.janelia.render.client.parameters.MatchRenderParameters;
import org.janelia.render.client.spark.cache.CanvasDataCache;
import org.janelia.render.client.spark.cache.CanvasFileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating and storing DMesh point matches for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class DMeshPointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MatchDataClientParameters matchClient = new MatchDataClientParameters();

        @ParametersDelegate
        public MatchRenderParameters matchRender = new MatchRenderParameters();

        @ParametersDelegate
        public MatchDerivationParameters match = new MatchDerivationParameters();

        @Parameter(
                names = "--pairJson",
                description = "JSON file where tile pairs are stored (.json, .gz, or .zip)",
                required = true,
                order = 5)
        public String pairJson;

        @Parameter(
                names = "--format",
                description = "Format for rendered canvases ('jpg', 'png', 'tif')",
                required = false)
        public String format = Utils.PNG_FORMAT;

        @Parameter(
                names = "--dMeshScript",
                description = "Script for launching DMesh",
                required = false)
        public String dMeshScript = "/groups/flyTEM/flyTEM/match/dmesh/run_ptest.sh";

        @Parameter(
                names = "--dMeshParameters",
                description = "File containing DMesh parameters",
                required = false)
        public String dMeshParameters = "/groups/flyTEM/flyTEM/match/dmesh/matchparams.txt";

        @Parameter(
                names = "--dMeshLogToolOutput",
                description = "Log DMesh tool output even when processing is successful",
                required = false,
                arity = 1)
        public boolean dMeshLogToolOutput = false;

        @Parameter(
                names = "--filterMatches",
                description = "Use RANSAC to filter matches",
                required = false,
                arity = 1)
        public boolean filterMatches = false;

        @Parameter(names = "--imageCacheParentDirectory",
                description = "Parent directory for cached (rendered) canvases",
                required = false)
        public String imageCacheParentDirectory = "/dev/shm";
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();

                // override SIFT parameter defaults
                parameters.matchRender.renderWithFilter = false;
                parameters.match.maxCacheGb = 20;

                parameters.parse(args, DMeshPointMatchClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final DMeshPointMatchClient client = new DMeshPointMatchClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public DMeshPointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("DMeshPointMatchClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                RenderableCanvasIdPairsUtilities.load(parameters.pairJson);

        final String renderParametersUrlTemplateForRun =
                RenderableCanvasIdPairsUtilities.getRenderParametersUrlTemplateForRun(
                        renderableCanvasIdPairs,
                        parameters.matchClient.baseDataUrl,
                        parameters.matchRender.renderFullScaleWidth,
                        parameters.matchRender.renderFullScaleHeight,
                        parameters.matchRender.renderScale,
                        parameters.matchRender.renderWithFilter,
                        parameters.matchRender.renderWithoutMask);

        final long cacheMaxKilobytes = parameters.match.maxCacheGb * 1000000;

        final CanvasFileLoader fileLoader =
                new CanvasFileLoader(
                        renderParametersUrlTemplateForRun,
                        parameters.matchRender.fillWithNoise,
                        parameters.format,
                        new File(parameters.imageCacheParentDirectory));

        final DMeshTool dMeshTool = new DMeshTool(new File(parameters.dMeshScript),
                                                  new File(parameters.dMeshParameters),
                                                  parameters.dMeshLogToolOutput);

        final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(parameters.match.matchRod,
                                                                             parameters.match.matchModelType,
                                                                             parameters.match.matchIterations,
                                                                             parameters.match.matchMaxEpsilon,
                                                                             parameters.match.matchMinInlierRatio,
                                                                             parameters.match.matchMinNumInliers,
                                                                             parameters.match.matchMaxTrust,
                                                                             parameters.match.matchMaxNumInliers,
                                                                             parameters.filterMatches);

        final double renderScale = parameters.matchRender.renderScale;

        // broadcast to all nodes
        final Broadcast<Long> broadcastCacheMaxKilobytes = sparkContext.broadcast(cacheMaxKilobytes);
        final Broadcast<CanvasFileLoader> broadcastFileLoader = sparkContext.broadcast(fileLoader);
        final Broadcast<DMeshTool> broadcastDMeshTool = sparkContext.broadcast(dMeshTool);
        final Broadcast<CanvasFeatureMatcher> broadcastFeatureMatcher =
                sparkContext.broadcast(featureMatcher);


        final JavaRDD<OrderedCanvasIdPair> rddCanvasIdPairs =
                sparkContext.parallelize(renderableCanvasIdPairs.getNeighborPairs());

        final JavaRDD<CanvasMatches> rddMatches =
                rddCanvasIdPairs.mapPartitionsWithIndex(
                        (Function2<Integer, Iterator<OrderedCanvasIdPair>, Iterator<CanvasMatches>>)
                                (partitionIndex, pairIterator) -> {

                    LogUtilities.setupExecutorLog4j("partition " + partitionIndex);

                    final Logger log = LoggerFactory.getLogger(DMeshPointMatchClient.class);

                    final CanvasFileLoader fileLoader1 = broadcastFileLoader.getValue();
                    final CanvasDataCache dataCache =
                            CanvasDataCache.getSharedCache(broadcastCacheMaxKilobytes.getValue(),
                                                           fileLoader1);
                    final DMeshTool dMeshTool1 = broadcastDMeshTool.getValue();
                    final CanvasFeatureMatcher featureMatcher1 = broadcastFeatureMatcher.getValue();


                    final List<CanvasMatches> matchList = new ArrayList<>();
                    int pairCount = 0;

                    OrderedCanvasIdPair pair;
                    CanvasId p;
                    CanvasId q;
                    File pFile;
                    RenderParameters pRenderParameters;
                    File qFile;
                    RenderParameters qRenderParameters;
                    CanvasMatches pairMatches;
                    Matches inlierMatches;
                    while (pairIterator.hasNext()) {

                        pair = pairIterator.next();
                        pairCount++;

                        p = pair.getP();
                        q = pair.getQ();

                        pFile = dataCache.getRenderedImage(p);
                        pRenderParameters = dataCache.getRenderParameters(p);

                        qFile = dataCache.getRenderedImage(q);
                        qRenderParameters = dataCache.getRenderParameters(q);

                        pairMatches = dMeshTool1.run(p, pFile, pRenderParameters, q, qFile, qRenderParameters);

                        if (pairMatches.size() > 0) {

                            if (featureMatcher1.isFilterMatches()) {
                                inlierMatches = featureMatcher1.filterMatches(pairMatches.getMatches(),
                                                                              new AffineModel2D(),
                                                                              renderScale);
                            } else {
                                inlierMatches = pairMatches.getMatches();

                                // point matches must be stored in full scale coordinates
                                if (renderScale != 1.0) {
                                    scalePoints(inlierMatches.getPs(), renderScale);
                                    scalePoints(inlierMatches.getQs(), renderScale);
                                }
                            }

                            if (inlierMatches.getWs().length > 0) {
                                matchList.add(new CanvasMatches(p.getGroupId(), p.getId(),
                                                                q.getGroupId(), q.getId(),
                                                                inlierMatches));
                            }
                        }
                    }

                    log.info("rddMatches: derived matches for {} out of {} pairs, cache stats are {}",
                             matchList.size(), pairCount, dataCache.stats());

                    return matchList.iterator();
                },
                true
        );

        final JavaRDD<Integer> rddSavedMatchPairCounts = rddMatches.mapPartitionsWithIndex(
                new MatchStorageFunction(parameters.matchClient.baseDataUrl,
                                         parameters.matchClient.owner,
                                         parameters.matchClient.collection),
                true
        );

        final int numPartitions = rddSavedMatchPairCounts.getNumPartitions();

        LOG.info("run: {} partitions, debug string is: \n{}",
                 numPartitions, rddSavedMatchPairCounts.toDebugString());

        final List<Integer> matchPairCountList = rddSavedMatchPairCounts.collect();
        long total = 0;
        for (final Integer matchCount : matchPairCountList) {
            total += matchCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: saved {} match pairs on {} partitions", total, matchPairCountList.size());

        final List<Boolean> cleanupList = new ArrayList<>(numPartitions);
        for (int i = 0; i < numPartitions; i++) {
            cleanupList.add(false);
        }
        final JavaRDD<Boolean> rddCleanupList = sparkContext.parallelize(cleanupList, numPartitions);
        final JavaRDD<Integer> rddCleanupPartitionIndexes = rddCleanupList.mapPartitionsWithIndex(
                (Function2<Integer, Iterator<Boolean>, Iterator<Integer>>) (partitionIndex, v2) -> {
                    LogUtilities.setupExecutorLog4j("partition " + partitionIndex);

                    final CanvasFileLoader fileLoader1 = broadcastFileLoader.getValue();
                    fileLoader1.deleteRootDirectory();

                    return Collections.singletonList(partitionIndex).iterator();
                },
                true
        );

        final List<Integer> cleanedUpPartitionIndexList = rddCleanupPartitionIndexes.collect();

        LOG.info("run: cleaned up {} partitions", cleanedUpPartitionIndexList.size());

        sparkContext.stop();

    }

    private static void scalePoints(final double[][] points,
                                    final double renderScale) {
        for (int i = 0; i < points.length; i++) {
            for (int j = 0; j < points[i].length; j++) {
                points[i][j] = points[i][j] / renderScale;
            }
        }
    }


    private static final Logger LOG = LoggerFactory.getLogger(DMeshPointMatchClient.class);
}
