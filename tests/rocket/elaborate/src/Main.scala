package v.elaborate

import mainargs._

import chisel3._
import chisel3.aop.Select
import chisel3.aop.injecting.InjectingAspect
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.stage.ChiselGeneratorAnnotation
import firrtl.annotations.Annotation
import firrtl.options.TargetDirAnnotation
import firrtl.stage.{FirrtlStage, OutputFileAnnotation, RunFirrtlTransformAnnotation}
import firrtl.transforms.TopWiring.TopWiringTransform
import firrtl.{AnnotationSeq, VerilogEmitter}
import os.Path

import freechips.rocketchip.stage._
import freechips.rocketchip.system.RocketChipStage

object Main {
  @main def elaborate(
                      @arg(name="dir") dir: String,
                      @arg(name="top") top: String,
                      @arg(name="config") config: String
                    ) = {
    val annotations = Seq(
      new RocketChipStage,
    ).foldLeft(
      Seq(
        TargetDirAnnotation(dir),
        new TopModuleAnnotation(Class.forName(top)),
        new ConfigsAnnotation(Seq(config)),
      ): AnnotationSeq
    ) { case (annos, stage) => stage.transform(annos) }
  }
  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
