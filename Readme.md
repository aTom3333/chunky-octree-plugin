# Chunky-octree-plugin
This is a plugin for [Chunky][chunky] that adds more octree implementations.

## Installation
Download the plug-in .jar file from the release page add it as a plug-in from the Chunky Launcher.

## Usage
New octree implementations will be available to choose from in the _Advanced_ tab.
Beware, some of them cannot be used in some circumstances, read the following details about each for more information.

## Provided octree implementation

### Compressed Siblings Implementation
Available under the name `COMPRESSED_SIBLINGS`, this implementation reduce memory usage over
the Packed Implementation, but it comes with a number of caveats.

#### Comparison

Advantages of this implementation over Packed:
 - Reduce memory usage by about half.
 
Drawbacks of this implementation over Packed:
 - Cannot be built. This implementation cannot be used for building the tree using the _Load Selected Chunks_ or _Reload Chunks_ button.
   It can only be loaded from an existing dump (possibly made with any other implementation).
 - Two times slower. Measured render time for the same scene was doubled using this implementation. 

#### Implementation specific instructions

As this implementation cannot be built from scratch, it you want to use it you will have to
create a scene with a different implementation, load the chunks you wish to render and save the scene.
Then you need to edit `.json` file of the scene and change the `"octreeImplementation"` field to be 
`"COMPRESSED_SIBLINGS"` or, if the field doesn't exist, add it: `"octreeImplementation": "COMPRESSED_SIBLINGS"`.
When reloading this scene the Compressed Siblings Implementation will be used.

A use case of this implementation might be that you have enough memory to use a Packed Implementation
for your scene when alone, but you don't have enough memory to fit it and the frame buffer
(the buffer used to store the image being rendered). What you can do is set up a scene with
a small frame buffer (by choosing a small resolution), load your scene with the Packed Implementation, save it,
reload it with the Compressed Siblings Implementation (by modifying the `.json` file) and then you can change
the resolution to be whatever you want. 

#### Advanced options

In the _Advanced Octree Options_ tab added by this plug-in you can tweak some parameters to get
a good balance between memory saving and limited scene. In particular if you wish to render a very big scene
you should increase the number of `bytes for index`.

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

[chunky]: https://chunky.llbit.se/