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
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.*;
import net.minecraft.init.Blocks;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.Optional;


/*
 * So the way this code works is that it basically updates baritone's other programs like movement; by updating the cost inside each node; Baritone's other programs will use that node in moving the player
 * Without the path finding algorithm the baritone program just doesnt know the best path to choose
 * 
    Same as Dijkstra's we have to use the nodes and paths of baritone inorder to get it to work;
    We are still replacing Baritone's pathfinding algorithm with our own


 * Steps to implement Bellman Ford
 * ok so basically we make queue
 * insert start node at queue
 * then for the node in the start of the queue
 * then for every neighbor relax the edges
 * then add neighbor to the queue
 * do it all again
 * 
 * We also need to detect negative edges so that the algorithm doesnt go on forever
 * I dont actually know if baritone gives us negative edges but this is just in case
 * Use a hashmap for that to check how many times
 * 
 * 
 *  Ok so after we know how the code is structured
 * We still need to update baritone and get the nodes from baritone
 * 
 * 
 * 
 * 
 */



public final class BellmanFordPathFinder extends AbstractNodeCostSearch {

    // Added for Baritone to work
    private final CalculationContext calcContext;

    // Constructor for the class
    public BellmanFordPathFinder(int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
       
        super(startX, startY, startZ, goal, context);
   
        this.calcContext = context;
    }

    // Main method to calculate the path
    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        long startTime = System.currentTimeMillis();

        
        // Initialize the start node with the starting position and set its cost to 0
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;

        // Create a queue of to store nodes to be processed
        Queue<PathNode> queue = new LinkedList<>();
        queue.add(startNode);

        // Create a map to store how many times a node has been processed
        Map<Long, Integer> iterationCount = new HashMap<>();
        iterationCount.put((long) startNode.hashCode(), 1);

        // Create a MutableMoveResult object to store move results
        MutableMoveResult res = new MutableMoveResult();
        // Create a BetterWorldBorder object to handle world border checks
        BetterWorldBorder worldBorder = new BetterWorldBorder(calcContext.world.getWorldBorder());
        // Get all possible moves
        Moves[] allMoves = Moves.values();

        // Main loop to process nodes in the queue
        while (!queue.isEmpty() && !cancelRequested) {
            // Get the next node in the queue and remove it
            PathNode currentNode = queue.poll();
            mostRecentConsidered = currentNode;

            // Check if the current node is in the goal
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                // If it is, return the path
                return Optional.of(new Path(startNode, currentNode, mapSize(), goal, calcContext));
            }

            // Iterate over all possible moves
            for (Moves move : allMoves) {
                // Calculate the new position after applying the move
                int newX = currentNode.x + move.xOffset;
                int newY = currentNode.y + move.yOffset;
                int newZ = currentNode.z + move.zOffset;

                // Check if the new position is within the world border
                if (!worldBorder.entirelyContains(newX, newZ)) {
                    continue;
                }

                // Check if the new position is within the valid Y range
                if (newY > 256 || newY < 0) {
                    continue;
                }

                // Reset the MutableMoveResult object and apply the move
                res.reset();
                move.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);

                // Get the cost of the action
                double actionCost = res.cost;

                // Check if the action cost is infinite or invalid
                if (actionCost >= ActionCosts.COST_INF || actionCost <= 0 || Double.isNaN(actionCost)) {
                    continue;
                }

                // Get the neighbor node at the new position
                long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                // Calculate the total cost for the neighbor node
                double totalCost = currentNode.cost + actionCost;


                // This is where relaxation occurs
                // Check if the improvement in cost is significant
                if (neighbor.cost - totalCost > MIN_IMPROVEMENT) {
                    // If so, update the neighbor's previous node and cost
                    neighbor.previous = currentNode;
                    neighbor.cost = totalCost;

                    // If the neighbor is not already in the queue, add it and update its iteration count
                    if (!queue.contains(neighbor)) {
                        queue.add(neighbor);
                        iterationCount.put(hashCode, iterationCount.getOrDefault(hashCode, 0) + 1);

                        // Check for negative cycles (when a node is processed too many times)
                        if (iterationCount.get(hashCode) >= mapSize()) {
                            throw new IllegalStateException("Negative cycle detected");
                        }
                    }
                }
            }
        }

        // If the search is canceled, return an empty result
        if (cancelRequested) {
            return Optional.empty();
        }

        // If the search is not canceled, return the best path found so far
        System.out.println(mapSize() + " movements considered");
        System.out.println("Open set size: " + queue.size());
        System.out.println("PathNode map size: " + mapSize());
        System.out.println((int) (mapSize() * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
        Optional<IPath> result = bestSoFar(true, mapSize());
        if (result.isPresent()) {
            System.out.println("Took " + (System.currentTimeMillis() - startTime) + "ms, " + mapSize() + " movements considered");
        }
        return result;
        
    }
}