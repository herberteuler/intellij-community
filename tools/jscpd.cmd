:<<"::CMDLITERAL"
@ECHO OFF
GOTO :CMDSCRIPT
::CMDLITERAL

# IMPORTANT: Read community/tools/tool-wrapper.design.md before making ANY modifications to this file.

# jscpd (copy-paste detector) wrapper - Unix section
# Downloads and executes jscpd with version pinning and checksum verification
#
# This wrapper never runs a locally installed jscpd, because a clone baseline is valid only for the version that wrote it.
#
# IMPORTANT: After updating TOOL_VERSION or checksums, you MUST run:
#   TOOL_VERIFY_ALL_PLATFORMS=1 ./community/tools/jscpd.cmd
# to verify all platform checksums before committing.

set -eu

# jscpd configuration
export TOOL_NAME="jscpd"
export TOOL_VERSION="5.4.0"

# SHA-256 checksums for each platform
export TOOL_CHECKSUM_LINUX_X64="4c2819a5663e5418fe5dcf0faceca7f05c1f080d3c87fd57cc4ccf99ce857194"
export TOOL_CHECKSUM_LINUX_ARM64="e31c723f89228fbd2f4231c2fbaf885074d15d33c4dc6b93ba551a966efe3517"
export TOOL_CHECKSUM_WINDOWS_X64="fb23edc10657bf93a66f6f2be53f10335f1be1da9456c97107f98a9d0e21ce13"
export TOOL_CHECKSUM_WINDOWS_ARM64="8fd6225a728c4c342c1ba68bf0db6c336af92c9cc869739ab8cb8ca50e60fdd5"
export TOOL_CHECKSUM_MACOS_X64="866676d440854f2e6083c4a88f754856fb91ccdc16f59e49a64574cd35d3f48d"
export TOOL_CHECKSUM_MACOS_ARM64="75022b94cd6c05523a7a1639c3fff769d9a97f8c7ef3f6a614c6bee53b7e3bdd"

# Download URLs (direct GitHub releases)
export TOOL_URL_LINUX_X64="https://github.com/kucherenko/jscpd/releases/download/v${TOOL_VERSION}/jscpd-linux-x64-gnu.tar.gz"
export TOOL_URL_LINUX_ARM64="https://github.com/kucherenko/jscpd/releases/download/v${TOOL_VERSION}/jscpd-linux-arm64-gnu.tar.gz"
export TOOL_URL_WINDOWS_X64="https://github.com/kucherenko/jscpd/releases/download/v${TOOL_VERSION}/jscpd-windows-x64-msvc.tar.gz"
export TOOL_URL_WINDOWS_ARM64="https://github.com/kucherenko/jscpd/releases/download/v${TOOL_VERSION}/jscpd-windows-arm64-msvc.tar.gz"
export TOOL_URL_MACOS_X64="https://github.com/kucherenko/jscpd/releases/download/v${TOOL_VERSION}/jscpd-darwin-x64.tar.gz"
export TOOL_URL_MACOS_ARM64="https://github.com/kucherenko/jscpd/releases/download/v${TOOL_VERSION}/jscpd-darwin-arm64.tar.gz"

# Binary path within extracted archive
export TOOL_BINARY_UNIX="jscpd"
export TOOL_BINARY_WINDOWS="jscpd.exe"

# Invoke wrapper
root="$(cd "$(dirname "$0")"; pwd)"
exec "$root/tool-wrapper.sh" "$@"

:CMDSCRIPT

setlocal

REM IMPORTANT: Read community\tools\tool-wrapper.design.md before making ANY modifications to this file.

REM jscpd (copy-paste detector) wrapper - Windows section
REM This wrapper never runs a locally installed jscpd, because a clone baseline is valid only for the version that wrote it.
REM IMPORTANT: After updating TOOL_VERSION or checksums, you MUST run:
REM   set TOOL_VERIFY_ALL_PLATFORMS=1 && community\tools\jscpd.cmd
REM to verify all platform checksums before committing.

REM jscpd configuration
set "TOOL_NAME=jscpd"
set "TOOL_VERSION=5.4.0"

REM SHA-256 checksums for each platform
set "TOOL_CHECKSUM_LINUX_X64=4c2819a5663e5418fe5dcf0faceca7f05c1f080d3c87fd57cc4ccf99ce857194"
set "TOOL_CHECKSUM_LINUX_ARM64=e31c723f89228fbd2f4231c2fbaf885074d15d33c4dc6b93ba551a966efe3517"
set "TOOL_CHECKSUM_WINDOWS_X64=fb23edc10657bf93a66f6f2be53f10335f1be1da9456c97107f98a9d0e21ce13"
set "TOOL_CHECKSUM_WINDOWS_ARM64=8fd6225a728c4c342c1ba68bf0db6c336af92c9cc869739ab8cb8ca50e60fdd5"
set "TOOL_CHECKSUM_MACOS_X64=866676d440854f2e6083c4a88f754856fb91ccdc16f59e49a64574cd35d3f48d"
set "TOOL_CHECKSUM_MACOS_ARM64=75022b94cd6c05523a7a1639c3fff769d9a97f8c7ef3f6a614c6bee53b7e3bdd"

REM Download URLs (direct GitHub releases)
set "TOOL_URL_LINUX_X64=https://github.com/kucherenko/jscpd/releases/download/v%TOOL_VERSION%/jscpd-linux-x64-gnu.tar.gz"
set "TOOL_URL_LINUX_ARM64=https://github.com/kucherenko/jscpd/releases/download/v%TOOL_VERSION%/jscpd-linux-arm64-gnu.tar.gz"
set "TOOL_URL_WINDOWS_X64=https://github.com/kucherenko/jscpd/releases/download/v%TOOL_VERSION%/jscpd-windows-x64-msvc.tar.gz"
set "TOOL_URL_WINDOWS_ARM64=https://github.com/kucherenko/jscpd/releases/download/v%TOOL_VERSION%/jscpd-windows-arm64-msvc.tar.gz"
set "TOOL_URL_MACOS_X64=https://github.com/kucherenko/jscpd/releases/download/v%TOOL_VERSION%/jscpd-darwin-x64.tar.gz"
set "TOOL_URL_MACOS_ARM64=https://github.com/kucherenko/jscpd/releases/download/v%TOOL_VERSION%/jscpd-darwin-arm64.tar.gz"

REM Binary path within extracted archive
set "TOOL_BINARY_UNIX=jscpd"
set "TOOL_BINARY_WINDOWS=jscpd.exe"

REM Invoke wrapper
call "%~dp0tool-wrapper.cmd" %*
exit /B %ERRORLEVEL%
