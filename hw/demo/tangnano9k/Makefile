# Tang Nano 9K: Verilog ze Spinala -> yosys -> nextpnr-himbaechel -> gowin_pack.
# Narzedzia: oss-cad-suite (yosys, nextpnr-himbaechel, gowin_pack, openFPGALoader)
# albo yowasp: make YOSYS=yowasp-yosys NEXTPNR=yowasp-nextpnr-himbaechel
TOP     ?= SnakeDemoTangNano9k
DEVICE  := GW1NR-LV9QN88PC6/I5
FAMILY  := GW1N-9C
FREQ    := 27

YOSYS   ?= yosys
NEXTPNR ?= nextpnr-himbaechel
PACK    ?= gowin_pack
LOADER  ?= openFPGALoader

GEN   := hw/gen
BUILD := build
CST   := cst/$(TOP).cst

all: $(BUILD)/$(TOP).fs

gen $(GEN)/$(TOP).v:
	cd ../.. && sbt "demo-tangnano9k/runMain newhope.tangnano9k.$(TOP)Verilog"

$(BUILD)/$(TOP).json: $(GEN)/$(TOP).v
	mkdir -p $(BUILD)
	# keep_hierarchy (Spinal: BufferCC) psuje iopadmap w synth_gowin - zdjety
	$(YOSYS) -q -l $(BUILD)/yosys.log -p "read_verilog $<; attrmap -remove keep_hierarchy; synth_gowin -top $(TOP) -json $@"

$(BUILD)/$(TOP)_pnr.json: $(BUILD)/$(TOP).json $(CST)
	$(NEXTPNR) -q -l $(BUILD)/nextpnr.log --json $< --write $@ --freq $(FREQ) \
	  --device $(DEVICE) --vopt family=$(FAMILY) --vopt cst=$(CST)

$(BUILD)/$(TOP).fs: $(BUILD)/$(TOP)_pnr.json
	$(PACK) -d $(FAMILY) -o $@ $<

prog: $(BUILD)/$(TOP).fs        # do SRAM (do wylaczenia zasilania)
	$(LOADER) -b tangnano9k $<

flash: $(BUILD)/$(TOP).fs       # do flasha
	$(LOADER) -b tangnano9k -f $<

clean:
	rm -rf $(BUILD) $(GEN)

.PHONY: all gen prog flash clean
