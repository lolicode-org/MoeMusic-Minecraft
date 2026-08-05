# MoeMusic Velocity adapter

This module hosts MoeMusic at the Velocity proxy. It is a proxy-wide adapter:
the client-facing MoeMusic plugin messages terminate at Velocity, and player
sessions remain present while a player changes backend servers.

The proxy should not also run a server-side MoeMusic adapter for the same
player connection. Backend forwarding is a separate concern from the
client-facing MoeMusic channel.

Velocity provides the command source permission subject. An explicitly granted
permission allows an action, an explicitly denied permission rejects it, and
an undefined permission uses MoeMusic's default level (`0` is public). The
console is unrestricted.

Build with Java 25 and install the resulting shaded JAR in Velocity's `plugins`
directory. The `velocityApiVersion` Gradle property can override the documented
API version when testing another compatible Velocity API.

The command is registered as a native Velocity Brigadier tree. Velocity's API,
Adventure, and Brigadier classes are provided by the proxy. The standalone
MoeMusic plugin loader parent-provides Kotlin, kotlinx, SLF4J, and the
unrelocated MoeMusic API to source plugins; the adapter relocates its other
implementation libraries to avoid proxy-plugin conflicts. Source-plugin JARs
should therefore keep those host-provided dependencies compile-only.
