$version: "2.0"

namespace fullstack_scala.protocol

use alloy#simpleRestJson
use alloy#uuidFormat

@simpleRestJson
service MimaService {
  version: "1.0.0",
  operations: [GetComparison, CreateComparison]
}

@readonly
@http(method: "GET", uri: "/api/comparison/{id}", code: 200)
operation GetComparison {
  input := {
    @required
    @httpLabel
    id: ComparisonId
  }

  output := {
    @required
    comparison: Comparison,

    @required
    problems: ProblemsList
  }
}

@idempotent
@http(method: "PUT", uri: "/api/comparison", code: 200)
operation CreateComparison {
  input := {
    @required
    attributes: ComparisonAttributes
  }

  output := {
    @required
    comparisonId: ComparisonId 

    @required
    problems: ProblemsList 
  }

  errors: [CodeTooBig, InvalidScalaVersion, CompilationFailed]
}


@error("client")
@httpError(400)
structure InvalidScalaVersion {}

@error("client")
@httpError(400)
structure CodeTooBig {
  @required
  sizeBytes: Integer

  @required
  maxSizeBytes: Integer

  @required 
  which: CodeLabel
}

@error("client")
@httpError(400)
structure CompilationFailed {

  @required 
  which: CodeLabel

  @required
  errorOut: String
}

enum CodeLabel {
  AFTER
  BEFORE
}


list ProblemsList {
  member: Problem
}

structure Problem {
  message: String
}


structure Comparison {
  @required
  id: ComparisonId

  @required
  attributes: ComparisonAttributes
}

structure ComparisonAttributes {
  @required
  beforeScalaCode: ScalaCode

  @required
  afterScalaCode: ScalaCode

  @required
  scalaVersion: ScalaVersion
}

@uuidFormat
string ComparisonId
string ScalaCode
string ScalaVersion
