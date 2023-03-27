{
  description = "vector";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/master";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }@inputs:
    let
      overlay = import ./overlay.nix;
    in
    flake-utils.lib.eachDefaultSystem
      (system:
        let
          pkgs = import nixpkgs { inherit system; overlays = [ overlay ]; };
          deps = with pkgs; [
            rv32-clang
            myLLVM.bintools

            cmake
            libargs
            glog
            fmt
            (enableDebugging libspike)
            zlib

            mill
            python3
            go
            ammonite
            gnused
            coreutils
            gnumake
            gnugrep
            which
            parallel
            protobuf
            ninja
            verilator
            antlr4
            numactl
            dtc
            espresso
            circt
            buddy-mlir

	    yarn
          ];
        in
        {
          legacyPackages = pkgs;
          devShell = pkgs.mkShell.override { stdenv = pkgs.myLLVM.stdenv; } {
            buildInputs = deps;
          };
        }
      )
    // { inherit inputs; overlays.default = overlay; };
}
