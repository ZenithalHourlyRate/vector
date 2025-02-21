  memref.global "private" @gv_i32 : memref<10x10xi32>

  func.func @test() -> i32 {
      %c0 = arith.constant 0 : index

      %input1 = memref.get_global @gv_i32 : memref<10x10xi32>
      %input2 = memref.get_global @gv_i32 : memref<10x10xi32>
      %output = memref.get_global @gv_i32 : memref<10x10xi32>

      %c0_i32 = arith.constant 0 : i32
      %c1 = arith.constant 1 : index

      %sew = arith.constant 2 : i32
      %lmul = arith.constant 1 : i32
      %mask4 = arith.constant dense<1> : vector<[4]xi1>

      %aRow = memref.dim %input2, %c0 : memref<10x10xi32>
      %aCol = memref.dim %input2, %c1 : memref<10x10xi32>
      %bRow = memref.dim %output, %c0 : memref<10x10xi32>
      %bCol = memref.dim %output, %c1 : memref<10x10xi32>
      %bCol_i32 = arith.index_cast %bCol : index to i32
      affine.for %idx0 = %c0 to %bRow {
        affine.for %idx1 = %c0 to %aRow {
          affine.for %idx2 = %c0 to %aCol {
            %bEle = vector.load %input2[%idx1, %idx2] : memref<10x10xi32>, vector<[1]xi32>
            %bEle_vector = vector.broadcast %bEle : vector<[1]xi32> to vector<[4]xi32>
            %tmpAVL, %tmpIdx = scf.while (%avl = %bCol_i32, %idx = %c0) : (i32, index) -> (i32, index) {
              // If avl greater than zero.
              %cond = arith.cmpi sgt, %avl, %c0_i32 : i32
              // Pass avl, idx to the after region.
              scf.condition(%cond) %avl, %idx : i32, index
            } do {
            ^bb0(%avl : i32, %idx : index):
              // Perform the calculation according to the vl.
              %vl = rvv.setvl %avl, %sew, %lmul : i32

              %avl_ind = arith.index_cast %avl : i32 to index
              %curlen = arith.subi %bCol, %avl_ind : index
              %idx_in_aRow = arith.addi %idx0, %idx1 : index
              %idx_in_aCol = arith.addi %idx2, %curlen : index

              %input_vector = vector_exp.predication %mask4, %vl : vector<[4]xi1>, i32 {
                %ele = vector.load %input1[%idx_in_aRow, %idx_in_aCol] : memref<10x10xi32>, vector<[4]xi32>
                vector.yield %ele : vector<[4]xi32>
              } : vector<[4]xi32>
              %c_vector = vector_exp.predication %mask4, %vl : vector<[4]xi1>, i32 {
                %ele = vector.load %output[%idx0, %curlen] : memref<10x10xi32>, vector<[4]xi32>
                vector.yield %ele : vector<[4]xi32>
              } : vector<[4]xi32>
              %mul_vector = rvv.mul %input_vector, %bEle_vector, %vl : vector<[4]xi32>, vector<[4]xi32>, i32
              %result_vector = rvv.add %mul_vector, %c_vector, %vl : vector<[4]xi32>, vector<[4]xi32>, i32
              vector_exp.predication %mask4, %vl : vector<[4]xi1>, i32 {
                vector.store %result_vector, %output[%idx0, %curlen] : memref<10x10xi32>, vector<[4]xi32>
                vector.yield
              } : () -> ()
              // Update idx and avl.
              %vl_ind = arith.index_cast %vl : i32 to index
              %new_idx = arith.addi %idx, %vl_ind : index
              %new_avl = arith.subi %avl, %vl : i32
              scf.yield %new_avl, %new_idx : i32, index
            }
          }
        }
      }

      %result = vector.load %input1[%c0, %c0] : memref<10x10xi32>, vector<8xi32>

      %mask = arith.constant dense<1> : vector<8xi1>
      %c1_i32 = arith.constant 1 : i32
      %evl = arith.constant 8 : i32
      %res_reduce_add_mask_driven = "llvm.intr.vp.reduce.add" (%c1_i32, %result, %mask, %evl) :
            (i32, vector<8xi32>, vector<8xi1>, i32) -> i32

      return %res_reduce_add_mask_driven : i32
    }
