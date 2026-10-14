# Thin wrappers over the Maven wrapper, plus the targets that regenerate what
# the README shows. Needs JDK 21; `make docker-test` needs only Docker.
MVN ?= ./mvnw -q
JAR := target/deadline.jar

.PHONY: build test demo load check gif docker-test clean

build:
	$(MVN) package -DskipTests

test:
	$(MVN) verify

# One slow request through three separate JVMs, with and without deadlines.
demo: build
	java -jar $(JAR) demo --svg docs/timeline.svg

# The README's load table, and its claims checked.
load: build
	java -jar $(JAR) load --rate 30 --seconds 20 --check

check: test load

# Re-record docs/demo.gif from the real CLI (Docker: VHS plus a JRE).
gif: build
	docker build -q -t deadline-vhs -f docs/demo.Dockerfile docs
	docker run --rm -u $$(id -u):$$(id -g) -e HOME=/tmp -v $(CURDIR):/vhs deadline-vhs docs/demo.tape

# Everything in containers, for a machine without JDK 21.
docker-test:
	docker run --rm -u $$(id -u):$$(id -g) -e HOME=/tmp -v $(CURDIR):/w -w /w \
	  maven:3.9-eclipse-temurin-21 mvn -q -Dmaven.repo.local=/tmp/m2 verify

clean:
	rm -rf target
