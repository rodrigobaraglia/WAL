A Java WAL

This is a an append only log written in Java 25 leveraging Panama API to sidestep the garbage collector and Project Loom to swiftly test multiple concurrent writes. This is an educational project. It's goal is to achieve a better undestanding of WALs and to explore the possibilities for systems programming in the JVM platform.
The project has been approached with deliberate minimalism. At the moment there are no dependencies whatsoever and the project is built and run with a bash script. To run the tests, simply run:

```
./run.sh

```
