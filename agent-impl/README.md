# agent-impl

Contains the actual implementation of the agent, tracking memory and performing access checks.

This is a separate sub-project which produces a JAR with dependencies which is then included in the main agent JAR.
The main agent then adds the nested agent-impl JAR to the bootstrap classpath, which is necessary because the
instrumented `Unsafe` methods can only access classes on the bootstrap classpath.

See also [this Byte Buddy issue comment](https://github.com/raphw/byte-buddy/issues/597#issuecomment-458041738).

The classes of this sub-project are not part of the public API (regardless of their visibility) and are normally
not directly accessible by user code. Instead, all interaction goes through the public API of the agent.
