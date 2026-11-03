# iCESugar: Verilog ze Spinala -> yosys -> nextpnr-ice40 -> icepack.
# Narzedzia: oss-cad-suite (yosys, nextpnr-ice40, icepack) + icesprog
# (github.com/wuxx/icesugar/tools) albo yowasp:
#   make YOSYS=yowasp-yosys NEXTPNR=yowasp-nextpnr-ice40 ICEPACK=yowasp-icepack
# Bez icesprog: skopiowac build/<TOP>.bin na dysk iCELink.
TOP     ?= SnakeDemoIceSugar
FREQ    := 12

YOSYS   ?= yosys
NEXTPNR ?= nextpnr-ice40
ICEPACK ?= icepack
PROG    ?= icesprog

GEN   := hw/gen
BUILD := build
PCF   := pcf/$(TOP).pcf

all: $(BUILD)/$(TOP).bin

gen $(GEN)/$(TOP).v:
	cd ../.. && sbt "demo-icesugar/runMain newhope.icesugar.$(TOP)Verilog"

$(BUILD)/$(TOP).json: $(GEN)/$(TOP).v
	mkdir -p $(BUILD)
	$(YOSYS) -q -l $(BUILD)/yosys.log -p "read_verilog $<; synth_ice40 -top $(TOP) -json $@"

$(BUILD)/$(TOP).asc: $(BUILD)/$(TOP).json $(PCF)
	$(NEXTPNR) -q -l $(BUILD)/nextpnr.log --up5k --package sg48 --freq $(FREQ) \
	  --pcf $(PCF) --json $< --asc $@

$(BUILD)/$(TOP).bin: $(BUILD)/$(TOP).asc
	$(ICEPACK) $< $@

prog: $(BUILD)/$(TOP).bin
	$(PROG) $<

clean:
	rm -rf $(BUILD) $(GEN)

.PHONY: all gen prog clean
