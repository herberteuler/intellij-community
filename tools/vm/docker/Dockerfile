# The image of a Docker Linux worker (`--backend docker`). The lane controller builds it; nobody builds it by hand.
# The controller passes two build arguments. `AIR_BASE_IMAGE` is `DOCKER_BASE_IMAGE` from
# `.agents/skills/vm-ui-tests/provision/versions.env`: Ubuntu 26.04, pinned by digest.
# `AIR_GUEST_PACKAGES` is `GUEST_PACKAGES` from `crates/avl-host-sys/src/guest/linux.rs`, so the Tart Linux worker
# and this image install one package list.
# The image tag is a digest over this file, `air-display`, the base image and the package list.
# A change to one of them gives a new tag, and the controller then builds the image again.
# `AIR_IMAGE_REVISION` is that digest. The label holds it, so the controller can check an image it pulled from the
# registry by its tag before a worker runs on it (ADR 0184).
# The base image has the account `ubuntu` at uid 1000, on Ubuntu 24.04 and 26.04 alike. The worker account `admin`
# needs that uid, so the build removes `ubuntu` first.
# The image installs no recommended package. The fluxbox overlay below keeps the style from setting a wallpaper, so
# fluxbox never runs `fbsetbg`, which needs a wallpaper setter (`feh`) and opens an `xmessage` without one. The same
# line is `FLUXBOX_OVERLAY_CONTENT` in `crates/avl-guest/src/linux.rs`, which `provision-guest` writes on a Tart worker.
# ADR 0183 (`plugins/air/docs/decisions/`) records why a Linux worker may be a container.
ARG AIR_BASE_IMAGE
FROM ${AIR_BASE_IMAGE}
ARG AIR_GUEST_PACKAGES
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends sudo ca-certificates xz-utils ${AIR_GUEST_PACKAGES} \
 && rm -rf /var/lib/apt/lists/*
RUN printf 'background: unset\n' > /etc/X11/fluxbox/overlay
RUN userdel --remove ubuntu && useradd --uid 1000 --create-home --shell /bin/bash admin \
 && printf 'admin ALL=(ALL) NOPASSWD:ALL\n' > /etc/sudoers.d/admin && chmod 440 /etc/sudoers.d/admin
# The build sets the mode of the entrypoint, so the controller writes the build context as plain files on every host.
# `--chmod` needs BuildKit, which is the builder of every engine the controller supports.
COPY --chmod=0755 air-display /usr/local/sbin/air-display
# Last, so a new digest leaves the package layer in the build cache.
ARG AIR_IMAGE_REVISION
LABEL org.opencontainers.image.revision=${AIR_IMAGE_REVISION}
ENTRYPOINT ["/usr/local/sbin/air-display"]
