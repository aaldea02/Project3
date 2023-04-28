/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */



 
package baritone.behavior;

import java.util.concurrent.ExecutionException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import java.io.IOException;


import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;



import baritone.Baritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.*;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.interfaces.IGoalRenderPos;

import baritone.pathing.calc.BellmanFordPathFinder;
import baritone.pathing.calc.DijkstraPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {
    
    private static final String CONNECTION_URL = "jdbc:mysql://localhost:3306/MinecraftPathFinder?useSSL=false&serverTimezone=UTC&user=root&password=yaryar&jdbc:mysql-connector-java-8.0.28.jar=./logs/mysql-connector-java-8.0.28.jar";

    private static final Logger LOGGER = LogManager.getLogger("Baritone");



    private PathExecutor current;
    private PathExecutor next;

    private Goal goal;
    private CalculationContext context;

    /*eta*/
    private int ticksElapsedSoFar;
    private BetterBlockPos startPosition;

    private boolean safeToCancel;
    private boolean pauseRequestedLastTick;
    private boolean unpausedLastTick;
    private boolean pausedThisTick;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;

    private volatile AbstractNodeCostSearch inProgress;
    private final Object pathCalcLock = new Object();

    private final Object pathPlanLock = new Object();

    private boolean lastAutoJump;

    private BetterBlockPos expectedSegmentStart;

    private final LinkedBlockingQueue<PathEvent> toDispatch = new LinkedBlockingQueue<>();

    public PathingBehavior(Baritone baritone) {
        super(baritone);
    }

    private void queuePathEvent(PathEvent event) {
        toDispatch.add(event);
    }

    private void dispatchEvents() {
        ArrayList<PathEvent> curr = new ArrayList<>();
        toDispatch.drainTo(curr);
        calcFailedLastTick = curr.contains(PathEvent.CALC_FAILED);
        for (PathEvent event : curr) {
            baritone.getGameEventHandler().onPathEvent(event);
        }
    }

    @Override
    public void onTick(TickEvent event) {
        dispatchEvents();
        if (event.getType() == TickEvent.Type.OUT) {
            secretInternalSegmentCancel();
            baritone.getPathingControlManager().cancelEverything();
            return;
        }

        expectedSegmentStart = pathStart();
        baritone.getPathingControlManager().preTick();
        tickPath();
        ticksElapsedSoFar++;
        dispatchEvents();
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        if (isPathing()) {
            event.setState(current.isSprinting());
        }
    }

    private void tickPath() {
        pausedThisTick = false;
        if (pauseRequestedLastTick && safeToCancel) {
            pauseRequestedLastTick = false;
            if (unpausedLastTick) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
            unpausedLastTick = false;
            pausedThisTick = true;
            return;
        }
        unpausedLastTick = true;
        if (cancelRequested) {
            cancelRequested = false;
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    // we are calculating
                    // are we calculating the right thing though? 🤔
                    BetterBlockPos calcFrom = inProgress.getStart();
                    Optional<IPath> currentBest = inProgress.bestPathSoFar();
                    if ((current == null || !current.getPath().getDest().equals(calcFrom)) // if current ends in inProgress's start, then we're ok
                            && !calcFrom.equals(ctx.playerFeet()) && !calcFrom.equals(expectedSegmentStart) // if current starts in our playerFeet or pathStart, then we're ok
                            && (!currentBest.isPresent() || (!currentBest.get().positions().contains(ctx.playerFeet()) && !currentBest.get().positions().contains(expectedSegmentStart))) // if
                    ) {
                        // when it was *just* started, currentBest will be empty so we need to also check calcFrom since that's always present
                        inProgress.cancel(); // cancellation doesn't dispatch any events
                    }
                }
            }
            if (current == null) {
                return;
            }
            safeToCancel = current.onTick();
            if (current.failed() || current.finished()) {
                current = null;
                if (goal == null || goal.isInGoal(ctx.playerFeet())) {
                    logDebug("All done. At " + goal);
                    queuePathEvent(PathEvent.AT_GOAL);
                    next = null;
                    if (Baritone.settings().disconnectOnArrival.value) {
                        ctx.world().sendQuittingDisconnectingPacket();
                    }
                    return;
                }
                if (next != null && !next.getPath().positions().contains(ctx.playerFeet()) && !next.getPath().positions().contains(expectedSegmentStart)) { // can contain either one
                    // if the current path failed, we may not actually be on the next one, so make sure
                    logDebug("Discarding next path as it does not contain current position");
                    // for example if we had a nicely planned ahead path that starts where current ends
                    // that's all fine and good
                    // but if we fail in the middle of current
                    // we're nowhere close to our planned ahead path
                    // so need to discard it sadly.
                    queuePathEvent(PathEvent.DISCARD_NEXT);
                    next = null;
                }
                if (next != null) {
                    logDebug("Continuing on to planned next path");
                    queuePathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
                    current = next;
                    next = null;
                    current.onTick(); // don't waste a tick doing nothing, get started right away
                    return;
                }
                // at this point, current just ended, but we aren't in the goal and have no plan for the future
                synchronized (pathCalcLock) {
                    if (inProgress != null) {
                        queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
                        return;
                    }
                    // we aren't calculating
                    queuePathEvent(PathEvent.CALC_STARTED);
                    findPathInNewThread(expectedSegmentStart, true, context);
                }
                return;
            }
            // at this point, we know current is in progress
            if (safeToCancel && next != null && next.snipsnapifpossible()) {
                // a movement just ended; jump directly onto the next path
                logDebug("Splicing into planned next path early...");
                queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
                current = next;
                next = null;
                current.onTick();
                return;
            }
            if (Baritone.settings().splicePath.value) {
                current = current.trySplice(next);
            }
            if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
                next = null;
            }
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    // if we aren't calculating right now
                    return;
                }
                if (next != null) {
                    // and we have no plan for what to do next
                    return;
                }
                if (goal == null || goal.isInGoal(current.getPath().getDest())) {
                    // and this path doesn't get us all the way there
                    return;
                }
                if (ticksRemainingInSegment(false).get() < Baritone.settings().planningTickLookahead.value) {
                    // and this path has 7.5 seconds or less left
                    // don't include the current movement so a very long last movement (e.g. descend) doesn't trip it up
                    // if we actually included current, it wouldn't start planning ahead until the last movement was done, if the last movement took more than 7.5 seconds on its own
                    logDebug("Path almost over. Planning ahead...");
                    queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
                    findPathInNewThread(current.getPath().getDest(), false, context);
                }
            }
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (current != null) {
            switch (event.getState()) {
                case PRE:
                    lastAutoJump = mc.gameSettings.autoJump;
                    mc.gameSettings.autoJump = false;
                    break;
                case POST:
                    mc.gameSettings.autoJump = lastAutoJump;
                    break;
                default:
                    break;
            }
        }
    }

    public void secretInternalSetGoal(Goal goal) {
        this.goal = goal;
    }

    public boolean secretInternalSetGoalAndPath(PathingCommand command) {
        secretInternalSetGoal(command.goal);
        if (command instanceof PathingCommandContext) {
            context = ((PathingCommandContext) command).desiredCalcContext;
        } else {
            context = new CalculationContext(baritone, true);
        }
        if (goal == null) {
            return false;
        }
        if (goal.isInGoal(ctx.playerFeet()) || goal.isInGoal(expectedSegmentStart)) {
            return false;
        }
        synchronized (pathPlanLock) {
            if (current != null) {
                return false;
            }
            synchronized (pathCalcLock) {
                if (inProgress != null) {
                    return false;
                }
                queuePathEvent(PathEvent.CALC_STARTED);
                findPathInNewThread(expectedSegmentStart, true, context);
                return true;
            }
        }
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public boolean isPathing() {
        return hasPath() && !pausedThisTick;
    }

    @Override
    public PathExecutor getCurrent() {
        return current;
    }

    @Override
    public PathExecutor getNext() {
        return next;
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.ofNullable(inProgress);
    }

    public boolean isSafeToCancel() {
        return current == null || safeToCancel;
    }

    public void requestPause() {
        pauseRequestedLastTick = true;
    }

    public boolean cancelSegmentIfSafe() {
        if (isSafeToCancel()) {
            secretInternalSegmentCancel();
            return true;
        }
        return false;
    }

    @Override
    public boolean cancelEverything() {
        boolean doIt = isSafeToCancel();
        if (doIt) {
            secretInternalSegmentCancel();
        }
        baritone.getPathingControlManager().cancelEverything(); // regardless of if we can stop the current segment, we can still stop the processes
        return doIt;
    }

    public boolean calcFailedLastTick() { // NOT exposed on public api
        return calcFailedLastTick;
    }

    public void softCancelIfSafe() {
        synchronized (pathPlanLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel); // only cancel ours
            if (!isSafeToCancel()) {
                return;
            }
            current = null;
            next = null;
        }
        cancelRequested = true;
        // do everything BUT clear keys
    }

    // just cancel the current path
    private void secretInternalSegmentCancel() {
        queuePathEvent(PathEvent.CANCELED);
        synchronized (pathPlanLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
            if (current != null) {
                current = null;
                next = null;
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
        }
    }

    @Override
    public void forceCancel() { // exposed on public api because :sob:
        cancelEverything();
        secretInternalSegmentCancel();
        synchronized (pathCalcLock) {
            inProgress = null;
        }
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    public Optional<Double> estimatedTicksToGoal() {
        BetterBlockPos currentPos = ctx.playerFeet();
        if (goal == null || currentPos == null || startPosition == null) {
            return Optional.empty();
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            resetEstimatedTicksToGoal();
            return Optional.of(0.0);
        }
        if (ticksElapsedSoFar == 0) {
            return Optional.empty();
        }
        double current = goal.heuristic(currentPos.x, currentPos.y, currentPos.z);
        double start = goal.heuristic(startPosition.x, startPosition.y, startPosition.z);
        if (current == start) {// can't check above because current and start can be equal even if currentPos and startPosition are not
            return Optional.empty();
        }
        double eta = Math.abs(current - goal.heuristic()) * ticksElapsedSoFar / Math.abs(start - current);
        return Optional.of(eta);
    }

    private void resetEstimatedTicksToGoal() {
        resetEstimatedTicksToGoal(expectedSegmentStart);
    }

    private void resetEstimatedTicksToGoal(BlockPos start) {
        resetEstimatedTicksToGoal(new BetterBlockPos(start));
    }

    private void resetEstimatedTicksToGoal(BetterBlockPos start) {
        ticksElapsedSoFar = 0;
        startPosition = start;
    }

    /**
     * See issue #209
     *
     * @return The starting {@link BlockPos} for a new path
     */
    public BetterBlockPos pathStart() { // TODO move to a helper or util class
        BetterBlockPos feet = ctx.playerFeet();
        if (!MovementHelper.canWalkOn(ctx, feet.down())) {
            if (ctx.player().onGround) {
                double playerX = ctx.player().posX;
                double playerZ = ctx.player().posZ;
                ArrayList<BetterBlockPos> closest = new ArrayList<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
                    }
                }
                closest.sort(Comparator.comparingDouble(pos -> ((pos.x + 0.5D) - playerX) * ((pos.x + 0.5D) - playerX) + ((pos.z + 0.5D) - playerZ) * ((pos.z + 0.5D) - playerZ)));
                for (int i = 0; i < 4; i++) {
                    BetterBlockPos possibleSupport = closest.get(i);
                    double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
                    double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);
                    if (xDist > 0.8 && zDist > 0.8) {
                        // can't possibly be sneaking off of this one, we're too far away
                        continue;
                    }
                    if (MovementHelper.canWalkOn(ctx, possibleSupport.down()) && MovementHelper.canWalkThrough(ctx, possibleSupport) && MovementHelper.canWalkThrough(ctx, possibleSupport.up())) {
                        // this is plausible
                        //logDebug("Faking path start assuming player is standing off the edge of a block");
                        return possibleSupport;
                    }
                }

            } else {
                // !onGround
                // we're in the middle of a jump
                if (MovementHelper.canWalkOn(ctx, feet.down().down())) {
                    //logDebug("Faking path start assuming player is midair and falling");
                    return feet.down();
                }
            }
        }
        return feet;
    }

    /**
     * In a new thread, pathfind to target blockpos
     *
     * @param start
     * @param talkAboutIt
     */
    private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt, CalculationContext context) {
        // this must be called with synchronization on pathCalcLock!
        // actually, we can check this, muahaha
        if (!Thread.holdsLock(pathCalcLock)) {
            throw new IllegalStateException("Must be called with synchronization on pathCalcLock");
            // why do it this way? it's already indented so much that putting the whole thing in a synchronized(pathCalcLock) was just too much lol
        }
        if (inProgress != null) {
            throw new IllegalStateException("Already doing it"); // should have been checked by caller
        }
        if (!context.safeForThreadedUse) {
            throw new IllegalStateException("Improper context thread safety level");
        }
        Goal goal = this.goal;
        if (goal == null) {
            logDebug("no goal"); // TODO should this be an exception too? definitely should be checked by caller
            return;
        }
        long primaryTimeout;
        long failureTimeout;
        if (current == null) {
            primaryTimeout = Baritone.settings().primaryTimeoutMS.value;
            failureTimeout = Baritone.settings().failureTimeoutMS.value;
        } else {
            primaryTimeout = Baritone.settings().planAheadPrimaryTimeoutMS.value;
            failureTimeout = Baritone.settings().planAheadFailureTimeoutMS.value;
        }
        AbstractNodeCostSearch pathfinder = createPathfinder(start, goal, current == null ? null : current.getPath(), context);
        if (!Objects.equals(pathfinder.getGoal(), goal)) { // will return the exact same object if simplification didn't happen
            logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
        }
        inProgress = pathfinder;
        Baritone.getExecutor().execute(() -> {
            if (talkAboutIt) {
                logDebug("Starting to search forrrrrr path from " + start + " to " + goal);
            }

            PathCalculationResult calcResult = pathfinder.calculate(primaryTimeout, failureTimeout);
            synchronized (pathPlanLock) {
                Optional<PathExecutor> executor = calcResult.getPath().map(p -> new PathExecutor(PathingBehavior.this, p));
                if (current == null) {
                    if (executor.isPresent()) {
                        if (executor.get().getPath().positions().contains(expectedSegmentStart)) {
                            queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                            current = executor.get();
                            resetEstimatedTicksToGoal(start);
                        } else {
                            logDebug("Warning: discarding orphan path segment with incorrect start");
                        }
                    } else {
                        if (calcResult.getType() != PathCalculationResult.Type.CANCELLATION && calcResult.getType() != PathCalculationResult.Type.EXCEPTION) {
                            // don't dispatch CALC_FAILED on cancellation
                            queuePathEvent(PathEvent.CALC_FAILED);
                        }
                    }
                } else {
                    if (next == null) {
                        if (executor.isPresent()) {
                            if (executor.get().getPath().getSrc().equals(current.getPath().getDest())) {
                                queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                                next = executor.get();
                            } else {
                                logDebug("Warning: discarding orphan next segment with incorrect start");
                            }
                        } else {
                            queuePathEvent(PathEvent.NEXT_CALC_FAILED);
                        }
                    } else {
                        //throw new IllegalStateException("I have no idea what to do with this path");
                        // no point in throwing an exception here, and it gets it stuck with inProgress being not null
                        logDirect("Warning: PathingBehaivor illegal state! Discarding invalid path!");
                    }
                }
                if (talkAboutIt && current != null && current.getPath() != null) {
                    if (goal.isInGoal(current.getPath().getDest())) {
                        logDebug("Finished finding aaaaa path from " + start + " to " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                    } else {
                        logDebug("Found path segmenttttt from " + start + " towards " + goal + ". " + current.getPath().getNumNodesConsidered() + " nodes considered");
                    }
                }
                synchronized (pathCalcLock) {
                    inProgress = null;
                }
            }
        });
    }


public static final String pathFinderAlgorithm = "BellmanFord"; // Change this value to "Dijkstra" if you want to use Dijkstra's algorithm

private static AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
    System.out.println("Creating pathfinder..." + pathFinderAlgorithm); // Add this line to check if the method is called
    LOGGER.info("Creating pathfinder...");

    try {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
    } catch (ClassNotFoundException e) {
        System.err.println("Failed to load SQL Server JDBC driver");
        LOGGER.error("Failed to load SQL Server JDBC driver", e);

    
        e.printStackTrace();
    }

    final Goal transformed;
    if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos) {
        BlockPos pos = ((IGoalRenderPos) goal).getGoalPos();
        if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
            transformed = new GoalXZ(pos.getX(), pos.getZ());
        } else {
            transformed = goal;
        }
    } else {
        transformed = goal;
    }

    Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);

    // Choose the algorithm to use based on the pathFinderAlgorithm setting
    AbstractNodeCostSearch result;
    long startTime, elapsedTimeBellmanFord = 0, elapsedTimeDijkstra = 0;

    if (pathFinderAlgorithm.equals("BellmanFord")) {
        startTime = System.nanoTime();
        result = new BellmanFordPathFinder(start.getX(), start.getY(), start.getZ(), transformed, favoring, context);
        elapsedTimeBellmanFord = System.nanoTime() - startTime;
        System.out.println("Bellman Ford elapsed time: " + elapsedTimeBellmanFord + " ns");
        LOGGER.info("Bellman Ford elapsed time: " + elapsedTimeBellmanFord + " ns");
    } else {
        startTime = System.nanoTime();
        result = new DijkstraPathFinder(start.getX(), start.getY(), start.getZ(), transformed, favoring, context);
        elapsedTimeDijkstra = System.nanoTime() - startTime;
        System.out.println("Dijkstra elapsed time: " + elapsedTimeDijkstra + " ns");        
        LOGGER.info("Dijkstra elapsed time: " + elapsedTimeDijkstra + " ns");
    }

    // Log the results to a text file
    logResultsToCsvFile(elapsedTimeBellmanFord, elapsedTimeDijkstra, start.getX(), start.getY(), start.getZ());
    logResultsToTextFile(elapsedTimeBellmanFord, elapsedTimeDijkstra, start.getX(), start.getY(), start.getZ());
    logResultsToCsvFile(elapsedTimeBellmanFord, elapsedTimeDijkstra, start.getX(), start.getY(), start.getZ());



    System.out.println("Pathfinder created"); // Add this line to check if the method is executed successfully
    LOGGER.info("Creating pathfinder...");
    return result;
}

private static void logResultsToCsvFile(long elapsedTimeBellmanFord, long elapsedTimeDijkstra, int x, int y, int z) {
    try {
        String logEntry = String.format("%d, %d, %d, %d, %d%n", x, y, z, elapsedTimeBellmanFord, elapsedTimeDijkstra);

        // Set the paths to the logs folders
        Path logFolderPath1 = Paths.get("C:\\Users\\sense\\Desktop\\DSA\\Minecraft\\logs");
        Path logFolderPath2 = Paths.get("logs");

        // Create the logs folders if they don't exist
        if (!Files.exists(logFolderPath1)) {
            Files.createDirectories(logFolderPath1);
        }
        if (!Files.exists(logFolderPath2)) {
            Files.createDirectories(logFolderPath2);
        }

        // Set the paths to the log.csv file inside the logs folders
        Path logFilePath1 = logFolderPath1.resolve("log.csv");
        Path logFilePath2 = logFolderPath2.resolve("log.csv");

        // Create the log.csv files if they don't exist
        if (!Files.exists(logFilePath1)) {
            Files.createFile(logFilePath1);
            Files.write(logFilePath1, "X, Y, Z, Bellman Ford Time, Dijkstra Time\n".getBytes(), StandardOpenOption.APPEND);
        }
        if (!Files.exists(logFilePath2)) {
            Files.createFile(logFilePath2);
            Files.write(logFilePath2, "X, Y, Z, Bellman Ford Time, Dijkstra Time\n".getBytes(), StandardOpenOption.APPEND);
        }

        // Append log entry to log.csv in both locations
        Files.write(logFilePath1, logEntry.getBytes(), StandardOpenOption.APPEND);
        Files.write(logFilePath2, logEntry.getBytes(), StandardOpenOption.APPEND);
    } catch (IOException e) {
        e.printStackTrace();
    }
}

private static void logResultsToTextFile(long elapsedTimeBellmanFord, long elapsedTimeDijkstra, int x, int y, int z) {
    try {
        String fastestMethod = elapsedTimeBellmanFord < elapsedTimeDijkstra ? "Bellman Ford" : "Dijkstra";
        String logEntry = String.format("X: %d, Y: %d, Z: %d, Time for Bellman Ford: %d ns, Time for Dijkstra: %d ns, Fastest method: %s%n",
                x, y, z, elapsedTimeBellmanFord, elapsedTimeDijkstra, fastestMethod);

        // Set the path to the logs folder
        Path logFolderPath = Paths.get("C:\\Users\\sense\\Desktop\\DSA\\Minecraft\\logs");

        // Create the logs folder if it doesn't exist
        if (!Files.exists(logFolderPath)) {
            Files.createDirectories(logFolderPath);
        }

        // Set the path to the log.txt file inside the logs folder
        Path logFilePath = logFolderPath.resolve("log.txt");

        // Create the log.txt file if it doesn't exist
        if (!Files.exists(logFilePath)) {
            Files.createFile(logFilePath);
        }

        // Append log entry to log.txt
        Files.write(logFilePath, logEntry.getBytes(), StandardOpenOption.APPEND);
    } catch (IOException e) {
        e.printStackTrace();
    }
}


private static void logResultsToDatabase(long elapsedTimeBellmanFord, long elapsedTimeDijkstra, int x, int y, int z) {
    try {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
    } catch (ClassNotFoundException e) {
        e.printStackTrace();
    }
    

    String insertQuery = "INSERT INTO PathFinderResults (bellman_ford_time, dijkstra_time, x, y, z) VALUES (?, ?, ?, ?, ?)";
    
    try (Connection connection = DriverManager.getConnection(CONNECTION_URL);
         PreparedStatement preparedStatement = connection.prepareStatement(insertQuery)) {
        
        preparedStatement.setLong(1, elapsedTimeBellmanFord);
        preparedStatement.setLong(2, elapsedTimeDijkstra);
        preparedStatement.setInt(3, x);
        preparedStatement.setInt(4, y);
        preparedStatement.setInt(5, z);

        preparedStatement.executeUpdate();
    } catch (SQLException e) {
        e.printStackTrace();
    }
}


    @Override
    public void onRenderPass(RenderEvent event) {
        PathRenderer.render(event, this);
    }
}
