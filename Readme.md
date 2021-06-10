# Chunky-octree-plugin
This is a plugin for [Chunky][chunky] that adds more octree implementations.

## Installation
Download the plug-in .jar file from the release page add it as a plug-in from the Chunky Launcher.

## Usage
New octree implementations will be available to choose from in the _Advanced_ tab.
Beware, some of them cannot be used in some circumstances, read the following details about each for more information.

## Provided octree implementation

### Disk Implementation
Available under the name `DISK`, this implementation stores the octree data on disk, in a temporary file
and uses cache to speed up the access to the data when needed. It allows, in theory, scenes of any size
to be loaded as they will be stored on disk and only a fraction of bounded size will be in RAM.

#### Comparison
Advantages of this implementation:
 - Can handle scene of any size, regardless of how much RAM is available (as long as enough disk space is available)

Drawbacks of this implementation:
 - Super slow. A large number of disk accesses need to be performed, even when trying to implement good caching. So
 this will be slow, especially if using magnetic disk.
 
#### Implementation Specific instructions
This implementation can be used like standard implementations. It can be used to built the octree and to render the scene.
The type of caching strategy will automatically switch from a strategy designed for read and write
but only supporting single thread access (for building the octree) to a strategy only allowing reading
but working with multiple threads.

 In the `Advanced Octree Options` the size and the number of caches to use can be specified. In my tests, on a magnetic disk,
 16KB seemed like the cache size that delivered the most performance. It may be different on your hardware so try tweaking the size if you feel like it.
 Increasing the number of cache improves performances (reduced number of disk access) at the cost of more RAM usage. Adpat this value
 in function of your resources. Keep in mind that for a single scene 2 octrees need to exist, this means
 that the total memory used for octree caches will be `2*cacheNumber*cacheSize`.
 Also keep in mind that the octrees are not the only things taking up memory when loading a scene.
 For a big scene with a lot of entities, a substancial amount of memory will be used to store those (limiting 
 the effectiveness of this implementation on loading huge scene with low amount of memory).

### Garbage-collected Implementation
Available under the name `GC_PACKED`. This implementation is similar to the built-in `PACKED` implementation 
with the difference that, instead of merging equal nodes after every insertion, merging is only done once in a while
before the array containing the data needs to be expanded. This is where the name "garbage-collected" comes from, 
like a garbage collector, we let the memory accumulate such as not wasting time when memory is abundant, but
we take action to free memory when it becomes scarce.

#### Comparison
Very similar to `PACKED` octree. Once fully built, there is no difference, so performance for rendering are the same.
The only differences are regarding loading the chunks.

Advantages of this implementation:
 - Speed up loading time most of the time
 
Drawbacks of this implementation:
 - Could in theory lead to higher peak memory usage if merging isn't done often enough (is if the threshold is too high).
 Doesn't seem to be an issue in practice.
 - Can lead to slower loading time with poorly chosen value of the threshold
 
#### Implementation Specific Instructions
This implementation can be used in the same way the `PACKED` implementation is used. It offers a parameter that can be
 changed to achieve better performance. The parameter is the number of `Inserts before merge`. To prevent merging the tree every time 
 a node is inserted, we only merge if the number of insertion since the last merge is greater that this threshold.
 If the threshold is too low, a lot of time will be lost trying to merge the tree only to free a handful of bytes, if it is to
 big, memory could be wasted and it may lead to slowdowns as well (due to more cache misses probably). The
 optimal value is scene and hardware dependant. the default value is `10000`.

### Small-leaf Implementation
Available under the name `SMALL_LEAF`, this implementation is similar to the `PACKED`
implementation but use a more compact representation for the leaves that are located at full depth.
As the depth of the tree is fixed and known, if we keep in memory the current depth while walking down
the tree, when we arrive at the last level (the one at full depth), we know the nodes are
leaves without having to read them. This means that we can use a compact representation for
those nodes that would not be suitable for branch nodes as they are never branch nodes.

#### Comparison
Advantages of this implementation:
 - Memory usage lower than `PACKED` implementation. A 16 bits integer is used to represent
 the leaves at full depth instead of a 32 bits integer. According to some measurements, leaves at
 full depth represent 2/3rd of the nodes, leading to an expected memory saving of around 33% (1/2 for 2/3rd of nodes).
 In practice measurements tends to agree to this around 33% memory saving.
 
Drawbacks of this implementation:
 - Slower than `PACKED` octree. The use of a bit of bitwise arithmetic is needed to work with the leaves at full
 depth. According to some measurement, this incurs a slowdown of about 15%.

### Dictionary Implementation
Available under the name `DICTIONARY` builds up on the idea of the Small-leaf implementation
by treating nodes that are at full depth differently than the others.
A dictionary of all combination of 2\*2\*2 blocks present in octree is kept and, in 
the octree, those groups of 2\*2\*2 blocks are referenced when they appear.
This means that a group of 8 blocks takes up only 4 bytes in the octree.
In the dictionary, each 2\*2\*2 groups takes up 16 bytes but each group usually appears
several time (while only being stored once) so in the end, this gives a great memory usage reduction.

#### Comparison
Advantages of this implementation:
 - Lower memory usage than `PACKED` or `SMALL_LEAF`. (No precise number for now)
 
Drawbacks of this implementation:
 - Maybe slower than `PACKED` octree during rendering. There is some additional `if`s
 that could slow a bit down but the difference is not that big.
 - Slower than `PACKED` octree during loading. During loading there is more work needed
 so it loads slower.
 
### Small DAG Implementation
Available under the name `DAG_TREE`. It is a tree containing small trees.
The small trees represents 64\*64\*64 blocks each. Nodes of the small trees are deduplicated, 
which technically make them directed acyclic graph and not just trees. The small trees (or rather DAG) can use only 16 bits
per nodes due to them being so small (limiting the max number of nodes it could have)

#### Comparison
Advantages of this implementation:
 - Lower memory usage than even `DICTIONARY`.
 
Drawbacks of this implementation:
 - Slower then `PACKED` during building and loading of the octree. (but not as slow as it was initially)
 - Slower than `PACKED` at rendering

[chunky]: https://chunky.llbit.se/