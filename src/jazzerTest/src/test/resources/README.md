# Jazzer test inputs

Inputs used by Jazzer for performing regression testing, see also [Jazzer documentation](https://github.com/CodeIntelligenceTesting/jazzer?tab=readme-ov-file#junit-5).

For the `..._error` test methods the `crash` file should lead to invalid memory access which causes an expected test
failure. For the `..._success` test methods the `no-crash` file is the same as the `crash` one for the corresponding
`..._error` test method, but it should not cause a test failure because the test method does not perform invalid memory
access.
