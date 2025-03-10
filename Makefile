init:
	git submodule update --init

patch:
	find patches -type f | awk -F/ '{print("(echo "$$0" && cd dependencies/" $$2 " && git apply -3 --ignore-space-change --ignore-whitespace ../../" $$0 ")")}' | sh

depatch:
	git submodule update
	git submodule foreach git restore -S -W .
	git submodule foreach git clean -xdf

compile:
	mill -i vector.compile

bump:
	git submodule foreach git stash
	git submodule update --remote
	git add dependencies

bsp:
	mill -i mill.bsp.BSP/install

update-patches:
	rm -rf patches
	sed '/BEGIN-PATCH/,/END-PATCH/!d;//d' readme.md | awk '{print("mkdir -p patches/" $$1 " && wget " $$2 " -P patches/" $$1 )}' | parallel
	git add patches

clean:
	git clean -fd

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat

ci-run:
	amm .github/scripts/ci.sc runTest . $(NAME) ./result-$${NAME:0:30}.md

ci-passed-tests:
	echo -n matrix= >> $$GITHUB_OUTPUT
	amm .github/scripts/ci.sc passedJson $(RUNNERS) .github/passed.txt ./passed.json
	cat ./passed.json >> $$GITHUB_OUTPUT

ci-unpassed-tests:
	echo -n matrix= >> $$GITHUB_OUTPUT
	amm .github/scripts/ci.sc unpassedJson $(RUNNERS) . .github/passed.txt ./unpassed.json
	cat ./unpassed.json >> $$GITHUB_OUTPUT

ci-all-tests:
	echo -n matrix= >> $$GITHUB_OUTPUT
	amm .github/scripts/ci.sc allJson $(RUNNERS) . ./all.json
	cat ./all.json >> $$GITHUB_OUTPUT
