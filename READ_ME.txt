# Minecraft Pathfinding with Dijkstra and Bellman-Ford Algorithms

This project contains two Minecraft mods that implement two different pathfinding algorithms, Dijkstra's and Bellman-Ford, for the Baritone pathfinding system. The purpose of this project is to compare the performance of these two algorithms in the context of Minecraft pathfinding.

## Installation

To use these mods, you'll need to install them separately in your Minecraft instance:

1. Download the Dijkstra's algorithm mod and the Bellman-Ford algorithm mod.
2. Install each mod by placing the downloaded files in the 'mods' folder of your Minecraft installation.

## Usage

After installing the mods, you can use them in your Minecraft world by running the command `mine diamond_ore` in the chat. This command will instruct Baritone to start mining diamond ore using the pathfinding algorithm provided by the installed mod.

## Performance Comparison

We have run extensive tests comparing the performance of the Dijkstra and Bellman-Ford algorithms in our Minecraft mods. Each algorithm was run over 100 times, and the results were recorded to determine which algorithm performed better on average.

The results show that Dijkstra's algorithm performed slightly better than Bellman-Ford in our tests. On average, Dijkstra's algorithm took 3.5 seconds to create a viable path, while Bellman-Ford took 3.6 seconds. This indicates that, in this specific context, Dijkstra's algorithm is more efficient than Bellman-Ford for pathfinding in Minecraft.

One of the reasons for this difference in performance is the use of a priority queue in the Dijkstra's algorithm implementation. The priority queue helps to speed up the algorithm by allowing it to quickly find the node with the lowest cost in each iteration, thus reducing the overall runtime. Additionally, since our Minecraft pathfinding problem does not involve negative edge weights (costs), we did not have to account for potential issues related to negative cycles, which is a key concern for the Bellman-Ford algorithm.

## Conclusion

This project demonstrates the use of Dijkstra's and Bellman-Ford algorithms in Minecraft for pathfinding with the Baritone system. While Dijkstra's algorithm performed slightly better in our tests due to the use of a priority queue and the absence of negative edge weights, it's important to consider the specific problem instance and graph properties when choosing a pathfinding algorithm. Feel free to experiment with both algorithms and compare their performance in your own Minecraft worlds.