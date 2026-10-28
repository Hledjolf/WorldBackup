// Game-agnostic plain Java: the blob store, manifests, retention, staged restore, the command line.
// No game API is ever on this project's classpath. --release 17, the level the C#, C++ and Lua ports start from.
tasks.withType<JavaCompile> { options.release.set(17) }
