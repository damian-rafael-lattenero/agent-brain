# JDK policy

Every JDK on the development machine is Amazon Corretto, installed through
Homebrew casks named `corretto@21`, `corretto@25` and so on; Temurin and the
`openjdk` formula are not used for projects. Gradle's toolchain is pinned to
vendor Amazon with automatic download disabled, so a missing Corretto fails the
build instead of silently fetching another distribution.
