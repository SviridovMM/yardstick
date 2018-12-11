package org.yardstickframework.runners;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.yardstickframework.BenchmarkUtils;
import org.yardstickframework.runners.context.NodeInfo;
import org.yardstickframework.runners.context.NodeStatus;
import org.yardstickframework.runners.context.NodeType;
import org.yardstickframework.runners.context.RunContext;
import org.yardstickframework.runners.context.RunMode;
import org.yardstickframework.runners.workers.host.CheckConnWorker;
import org.yardstickframework.runners.workers.host.CheckJavaWorker;
import org.yardstickframework.runners.workers.host.CollectWorker;
import org.yardstickframework.runners.workers.host.DeployWorker;
import org.yardstickframework.runners.workers.host.KillWorker;
import org.yardstickframework.runners.workers.node.CheckLogWorker;
import org.yardstickframework.runners.workers.node.NodeWorker;
import org.yardstickframework.runners.workers.node.RestartNodeWorker;
import org.yardstickframework.runners.workers.node.StartNodeWorker;
import org.yardstickframework.runners.workers.node.StopNodeWorker;
import org.yardstickframework.runners.workers.node.WaitNodeWorker;

public class FullRunner extends AbstractRunner {
    /**
     *
     * @param runCtx
     */
    public FullRunner(RunContext runCtx) {
        super(runCtx);
    }

    public static void main(String[] args) {
        RunContext runCtx = RunContext.getRunContext(args);

        FullRunner runner = new FullRunner(runCtx);

        runner.run1();
    }

    public int run1() {
        List<String> fullList = runCtx.getFullUniqList();

        checkPlain(new CheckConnWorker(runCtx, fullList));

        checkPlain(new CheckJavaWorker(runCtx, runCtx.getUniqHostsByMode(RunMode.PLAIN)));

        List<NodeType> dockerList = runCtx.getNodeTypes(RunMode.DOCKER);

        DockerRunner dockerRunner = new DockerRunner(runCtx);

        if (!dockerList.isEmpty()) {
            dockerRunner.check(dockerList);

            dockerRunner.cleanUp(dockerList, "before");
        }

        new KillWorker(runCtx, fullList).workOnHosts();

        new DeployWorker(runCtx, fullList).workOnHosts();

        if (!dockerList.isEmpty()) {
            dockerRunner.prepare(dockerList);

            dockerRunner.start(dockerList);
        }

        String cfgStr0 = runCtx.getProps().getProperty("CONFIGS").split(",")[0];

        List<NodeInfo> servRes = null;

        List<NodeInfo> drvrRes = null;

        if (runCtx.startServersOnce())
            servRes = startNodes(NodeType.SERVER, cfgStr0);

        for (String cfgStr : runCtx.getCfgList()) {
            if (!runCtx.startServersOnce())
                servRes = startNodes(NodeType.SERVER, cfgStr);

            checkLogs(servRes);

            waitForNodes(servRes, NodeStatus.RUNNING);

            drvrRes = startNodes(NodeType.DRIVER, cfgStr);

            checkLogs(drvrRes);

            waitForNodes(drvrRes, NodeStatus.RUNNING);

            ExecutorService restServ = Executors.newSingleThreadExecutor();

            final List<NodeInfo> forRestart = new ArrayList<>(servRes);

            Future<List<NodeInfo>> restFut = restServ.submit(new Callable<List<NodeInfo>>() {
                @Override public List<NodeInfo> call() throws Exception {
                    String threadName = String.format("Restart-handler-%s", BenchmarkUtils.hms());

                    Thread.currentThread().setName(threadName);

                    return restart(forRestart, cfgStr, NodeType.SERVER);
                }
            });

            waitForNodes(drvrRes, NodeStatus.NOT_RUNNING);

            restFut.cancel(true);

            restServ.shutdown();

            if (!runCtx.startServersOnce()) {
                stopNodes(servRes);

                waitForNodes(servRes, NodeStatus.NOT_RUNNING);
            }
        }

        if(runCtx.startServersOnce()){
            stopNodes(servRes);

            waitForNodes(servRes, NodeStatus.NOT_RUNNING);
        }


        if (!dockerList.isEmpty()) {
            dockerRunner.collect(dockerList);

            dockerRunner.cleanUp(dockerList, "after");
        }

        new CollectWorker(runCtx, runCtx.getFullUniqList()).workOnHosts();

        createCharts();

        return 0;
    }

    private List<NodeInfo> restart(List<NodeInfo> nodeList, String cfgStr, NodeType type){
        if(runCtx.getRestartContext(type) == null)
            return nodeList;

        RestartNodeWorker restWorker = new RestartNodeWorker(runCtx, nodeList, cfgStr);

        restWorker.runAsyncOnHost(true);

        return restWorker.workForNodes();
    }



    private void checkLogs(List<NodeInfo> list){
        NodeWorker checkWorker = new CheckLogWorker(runCtx,list);

        List<NodeInfo> resList = checkWorker.workForNodes();

        for (NodeInfo nodeInfo : resList){
            if(nodeInfo.nodeStatus() == NodeStatus.NOT_RUNNING)
                System.exit(1);
        }
    }

    private List<NodeInfo> startNodes(NodeType type, String cfgStr) {
        NodeWorker startServWorker = new StartNodeWorker(runCtx, runCtx.getNodes(type), cfgStr);

        return startServWorker.workForNodes();
    }

    private List<NodeInfo> stopNodes(List<NodeInfo> nodeList) {
        NodeWorker stopWorker = new StopNodeWorker(runCtx, nodeList);

        stopWorker.workForNodes();

        return null;
    }

    private void waitForNodes(List<NodeInfo> nodeList, NodeStatus expStatus) {
        NodeWorker waitWorker = new WaitNodeWorker(runCtx, nodeList, expStatus);

        waitWorker.workForNodes();
    }

//    private List<WorkResult> buildDockerImages(NodeType type) {
//        String imageNameProp = String.format("%s_DOCKER_IMAGE_NAME", type);
//
//        String imageName = runCtx.getProps().getProperty(imageNameProp);
//
//        String nameProp = String.format("%s_DOCKERFILE_NAME", type);
//        String pathProp = String.format("%s_DOCKERFILE_PATH", type);
//
//        if (runCtx.getProps().getProperty(nameProp) == null &&
//            runCtx.getProps().getProperty(pathProp) == null)
//            throw new IllegalArgumentException("Dockerfile name and path is not defined in property file.");
//
//        String dockerfilePath = runCtx.getProps().getProperty(pathProp) != null ?
//            runCtx.getProps().getProperty(pathProp) :
//            String.format("%s/config/%s", runCtx.getRemWorkDir(), runCtx.getProps().getProperty(nameProp));
//
//        String imageVer = runCtx.getMainDateTime();
//
//        List<String> hostList = type == NodeType.SERVER ?
//            runCtx.getServUniqList() :
//            runCtx.getDrvrUniqList();
//
//        DockerWorkContext dockerWorkCtx = new DockerWorkContext(hostList, type);
//
//        Worker buildDocWorker = new DockerBuildImagesWorker(runCtx, dockerWorkCtx);
//
//        return buildDocWorker.workOnHosts();
//    }

    private void createCharts() {
        String mainResDir = String.format("%s/output/result-%s", runCtx.getLocWorkDir(), runCtx.getMainDateTime());

        String cp = String.format("%s/libs/*", runCtx.getLocWorkDir());

        String mainClass = "org.yardstickframework.report.jfreechart.JFreeChartGraphPlotter";

        String jvmOpts = "-Xmx1g";

        String stdCharts = String.format("%s -cp %s %s -gm STANDARD -i %s", jvmOpts, cp, mainClass, mainResDir);

        CommandHandler hndl = new CommandHandler(runCtx);

        hndl.runLocalJava(stdCharts);

        String charts = String.format("%s -cp %s %s -i %s", jvmOpts, cp, mainClass, mainResDir);

        hndl.runLocalJava(charts);

        File outDir = new File(mainResDir).getParentFile();

        try {
            new CountDownLatch(1).await(3000L, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (outDir.exists() && outDir.isDirectory()) {
            File[] arr = outDir.listFiles();

            for (File resComp : arr) {
                if (resComp.getName().startsWith("results-compound")) {
                    String mvCmd = String.format("mv %s %s",
                        resComp.getAbsolutePath(), mainResDir);

                    runCmd(mvCmd);
                }
            }
        }
    }
}
