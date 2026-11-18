# Java 21 Security Manager proof of concept

This folder is deliberately standalone. It does not change NeuroCenter's runtime, app loader, installer or Java version.

The experiment runs an application JAR in a separate Java 21 process with a restrictive policy. The JAR may read and write only inside `target/poc-data/allowed/`. It then attempts several operations that should be denied.

## Requirements

- JDK 21 (not JDK 24 or newer)
- Maven 3.9+

## Run on Bash (Linux/macOS/Git Bash)

From this folder:

```bash
mvn --batch-mode --no-transfer-progress clean package

mkdir -p target/poc-data/allowed
printf 'allowed-fixture' > target/poc-data/allowed/fixture.txt
printf 'secret-outside-allowlist' > target/poc-data/forbidden.txt

java \
  -Djava.security.manager \
  "-Djava.security.policy==file:$PWD/src/main/resources/poc.policy" \
  -jar target/security-manager-poc-0.1.0.jar \
  "$PWD/target/poc-data/allowed" \
  "$PWD/target/poc-data/forbidden.txt"
```

The double equals sign in `java.security.policy==` replaces the default policy with this deliberately minimal policy. The Security Manager is installed at JVM startup.

## Expected results

- Reading and writing inside the granted directory succeeds.
- Reading, writing or deleting outside it is denied.
- A path-traversal attempt using `..` is denied.
- Opening a listening socket is denied.
- Loading a native library is denied.
- The application cannot disable the Security Manager.

The JVM will print deprecation warnings. That is expected: the Security Manager was deprecated for removal in JDK 17 and permanently disabled in JDK 24.

## What this proves — and what it does not

A successful run demonstrates that Java 21's policy checks block these ordinary Java API operations when the application runs under a restrictive policy.

It does **not** prove that arbitrary hostile code is safely sandboxed. In particular, native code, JVM vulnerabilities, confused-deputy bugs in trusted NeuroCenter code, and other escape techniques are outside this test. Security Manager is deprecated and is not a supported long-term sandbox architecture.

This experiment is not wired into the production application loader. NeuroCenter's current external apps run inside the host JVM; this proof of concept deliberately uses a separate process and must not be mistaken for an implementation of per-app isolation.
