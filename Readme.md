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
 - The maximum number of nodes that can be stored is lower (no exact number as nodes takes a variable space in this implementation).
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

[chunky]: https://chunky.llbit.se/