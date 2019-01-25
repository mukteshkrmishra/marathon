.PHONY: clean provision unit-test integration-test system-test compile universal-package

PKG_VER ?= $(shell ./../version)
PKG_REL ?= 0.1.$(shell date -u +'%Y%m%d%H%M%S')
PKG_COMMIT ?= $(shell ./../version commit)


help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

default: build


clean:
	rm -rf target

# Target aliases
provision: target/provisioned ## Provision the linux host, installing packages and whatnot on a Debian CI agent
compile: target/compiled.log ## Compile various projects
unit-test: target/unitTested.log ## Run the unit test suite
integration-test: target/integrationTested.log ## Run the integration test suite; some tests fail on OS X
system-test: target/systemTested.log ## Run the system integration test suite; does not work on OS X
universal-package: ../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz ## Package the universal artifact and docs tarballs (into target/universal{-docs}/)
docker-image: target/docker-image-$(PKG_VER)-built ## Build and tag the docker image locally
	amm pipeline buildDockerPackage

linux-packages: target/linux-packages-$(PKG_VER)-built ## Build Deb and RPM packages for supported Linux OSes; packages are built in the folder tools/packager/
	amm pipeline buildLinuxPackages

test-all-packages: target/packages-tested ## Tests both Linux native packages and Docker images

../target/universal-docs/marathon-docs-$(PKG_VER)-$(PKG_COMMIT).tgz ../target/universal/marathon-$(PKG_VER)-$(PKG_COMMIT).tgz:
	amm pipeline createTarballPackages


target/docker-image-$(PKG_VER)-built: compile
	amm pipeline buildDockerPackage
	touch $@

target/linux-packages-$(PKG_VER)-built: compile universal-package
	amm pipeline buildLinuxPackages
	touch $@

target/packages-tested: docker-image linux-packages
	amm pipeline testDockerAndLinuxPackages

target/compiled.log: $(shell find ../src)
	mkdir -p target
	amm pipeline compile --logFileName $@.work
	mv $@.work $@

target/unitTested.log: compile
	amm pipeline unitTest --logFileName $@.work
	touch $@

target/systemTested.log: compile
	amm pipeline systemTest --logFileName $@.work
	touch $@

target/integrationTested.log: compile
	amm pipeline integrationTest
	touch $@

target/provisioned:
	amm pipeline provisionHost
	mkdir -p target
	touch $@
