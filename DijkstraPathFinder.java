package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.openset.BinaryHeapOpenSet;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Moves;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import baritone.utils.pathing.MutableMoveResult;
import baritone.api.pathing.movement.ActionCosts;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.*;
import net.minecraft.init.Blocks;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.Optional;

/*
 * Ok we need to implement Dijkstra's with baritone; 
 * Studied how baritone's original algorithm updated other programs like their movement program so that the baritone code still works
 * We have to update everything the original algorithm updates or else the player kinda just stands there
 * 
 * We need to keep track of the lowest cost edge probably going to do it with Baritone's Binary Heap
 * The baritone node has a function that keeps track if the node is visited or not we just need to call update on the node to mark as visited
 * 
 * So basically we add node to binary heap then we update cost for every neighbor
 * Check if the neighbor is visited or not
 * Then go to the lowest cost neighbor inside the binary heap
 * Then do it all over again
 * 
 * 
 * 
 * We need to use a couple of data structures from baritone like the node and binary heap
 * for each node/block in minecraft baritone has a specific hash to identfy each one
 * We also need to update and feed back a Path object that baritone uses to move the player in minecraft
 * 

 * 
 */


public final class DijkstraPathFinder extends AbstractNodeCostSearch {

    private final CalculationContext calcContext;
    private static final int timeCheckInterval = 1000000;
    private boolean failing = false;

    public DijkstraPathFinder(int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
        super(startX, startY, startZ, goal, context);
        this.calcContext = context;
    }
    private void logToFile(String message) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
        LocalDateTime now = LocalDateTime.now();
        String timestamp = dtf.format(now);
    
        try (FileWriter fw = new FileWriter("logs.txt", true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println("[" + timestamp + "] " + message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        long algorithmStartTime = System.currentTimeMillis(); // Add timer start
        String startMessage = "Program started";
        logDebug(startMessage); // Log algorithm start
        logToFile(startMessage); // Log algorithm start to file
        
        
        
        
        // Initialize the start node and open set
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        
        // Store movement results for baritone
        MutableMoveResult res = new MutableMoveResult();
        BetterWorldBorder worldBorder = new BetterWorldBorder(calcContext.world.getWorldBorder());

        // Some logging I tried to implement
        long startTime = System.currentTimeMillis();

        // Baritone needs this to run
        boolean slowPath = Baritone.settings().slowPath.value;



        if (slowPath) {
            logDebug("slowPath is on, path timeout will be " + Baritone.settings().slowPathTimeoutMS.value + "ms instead of " + primaryTimeout + "ms");
        }
        long primaryTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : primaryTimeout);
        long failureTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : failureTimeout);


        int numNodes = 0;
        int numMovementsConsidered = 0;

        // Array of moves from Baritone; For the their movement program to work
        // As a plus we can get posible neighborsfrom this array
        Moves[] allMoves = Moves.values();
        
        // Main loop: keep processing nodes in the open set until it's empty or a cancel request is made
        while (!openSet.isEmpty() && !cancelRequested) {
            // Check for timeouts every timeCheckInterval nodes
            if ((numNodes & (timeCheckInterval - 1)) == 0) {
                long now = System.currentTimeMillis();
                if (now >= failureTimeoutTime || (!failing && now >= primaryTimeoutTime)) {
                    break;
                }
            }
            if (slowPath) {
                try {
                    Thread.sleep(Baritone.settings().slowPathTimeDelayMS.value);
                } catch (InterruptedException ignored) {}
            }
            // Get the node with the lowest cost in the open set
            PathNode currentNode = openSet.removeLowest();
            mostRecentConsidered = currentNode;
            numNodes++;

            // Check if the current node is within the goal
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
                return Optional.of(new Path(startNode, currentNode, numNodes, goal, calcContext));
            }
            
            // Iterate through all possible moves from the current node
            for (Moves move : allMoves) {

                // Create new position
                int newX = currentNode.x + move.xOffset;
                int newY = currentNode.y + move.yOffset;
                int newZ = currentNode.z + move.zOffset;
                

                // Check if the new position is within the world border
                if (!worldBorder.entirelyContains(newX, newZ)) {
                    continue;
                }
                // Check if the new position is within the valid Y-coordinate range
                if (newY > 256 || newY < 0) {
                    continue;
                }

                // Needed so player can move again
                res.reset();

                // Baritone method that actually moves the player
                move.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                
                
                numMovementsConsidered++;

                // Cost of Movement
                double actionCost = res.cost;


                // Check if the block at the new position is diamond ore
                BlockPos newPos = new BlockPos(res.x, res.y, res.z);
                if (calcContext.world.getBlockState(newPos).getBlock() == Blocks.DIAMOND_ORE) {
                    long timeToFindDiamond = System.currentTimeMillis() - algorithmStartTime; // Calculate time taken to find diamond
                    double timeToFindDiamondSeconds = timeToFindDiamond / 1000.0;
                    String logMessage = "Diamonds found in " + timeToFindDiamondSeconds + " seconds";
                    logDebug(logMessage); // Log to command
                    logToFile(logMessage); // Log to file
                }

                // Skip if the action cost is infinite or invalid
                if (actionCost >= ActionCosts.COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(move + " calculated implausible cost " + actionCost);
                }
                
                // Calculate the cost for the neighbor node
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                double totalCost = currentNode.cost + actionCost;
                
                // If the current total Cost is better than the neighbor's current cost, update the neighbor
                if (neighbor.cost - totalCost > MIN_IMPROVEMENT) {
                    neighbor.previous = currentNode;
                    neighbor.cost = totalCost;
                    // Check if the node is visited or not, if not then add it to the binary heap
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                    }
                }
            }
        }
    
        if (cancelRequested) {
            return Optional.empty();
        }
        System.out.println(numMovementsConsidered + " movements considered");
        System.out.println("Open set size: " + openSet.size());
        System.out.println("PathNode map size: " + mapSize());
        System.out.println((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
        Optional<IPath> result = bestSoFar(true, numNodes);
        if (result.isPresent()) {
            logDebug("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
        }
        return result;
    }
}
