package mimalyzer.frontend

import fullstack_scala.protocol.*
import smithy4s_fetch.SimpleRestJsonFetchClient
import com.raquo.laminar.api.L.*
import scala.scalajs.js.Promise
import org.scalajs.dom
import fullstack_scala.protocol.CodeLabel.AFTER
import fullstack_scala.protocol.CodeLabel.BEFORE
import scalajs.js

def createApiClient(uri: String) = SimpleRestJsonFetchClient(
  MimaService,
  uri
).make

extension [T](p: => Promise[T]) inline def stream = EventStream.fromJsPromise(p)

enum Action:
  case Submit

enum Result:
  case NoProblems
  case Problems(mima: Option[String] = None, tastyMima: Option[String])
  case Error(msg: String)
  case Waiting

def stateful(key: String, default: String) =
  val v = Var(
    Option(dom.window.localStorage.getItem(key))
      .getOrElse(default)
  )

  val b = v --> { value =>
    dom.window.localStorage.setItem(key, value)
  }

  v -> b
end stateful

@main def hello =
  val (oldScalaCode, oldSave) =
    stateful("old-scala-code", "package hello\nclass X {def x: Int = ???}")

  val (newScalaCode, newSave) =
    stateful("new-scala-code", "package hello\nclass X {def y: Int = ???}")

  val (scalaVersion, versionSave) =
    stateful("scala-version-enum", ScalaVersion.SCALA_213.value)

  val result = Var(Option.empty[Result])
  val actionBus = EventBus[Action]()

  val apiClient = createApiClient(dom.window.location.href)

  val saveState =
    Seq(
      oldSave,
      newSave,
      versionSave
    )

  val handleEvents =
    actionBus.events.withCurrentValueOf(
      oldScalaCode,
      newScalaCode,
      scalaVersion
    ) --> { case (Action.Submit, old, nw, sv) =>
      val attributes =
        ComparisonAttributes(
          beforeScalaCode = ScalaCode(old),
          afterScalaCode = ScalaCode(nw),
          scalaVersion = ScalaVersion.values.find(_.value == sv).get
        )

      result.set(Some(Result.Waiting))

      given Stability = Stability()

      exponentialFetch(() => apiClient.createComparison(attributes))
        .`then`(
          good =>
            result.set(
              Option(
                if good.mimaProblems.isEmpty && good.tastyMimaProblems.isEmpty
                then Result.NoProblems
                else
                  Result.Problems(
                    mima = good.mimaProblems.map(
                      _.problems
                        .flatMap(_.message)
                        .map("- " + _)
                        .mkString("\n")
                    ),
                    tastyMima = good.tastyMimaProblems.map(
                      _.problems
                        .flatMap(_.message)
                        .map("- " + _)
                        .mkString("\n")
                    )
                  )
              )
            ),
          bad =>
            bad match
              case e: CodeTooBig =>
                val lab = e.which match
                  case AFTER  => "New"
                  case BEFORE => "Old"

                result.set(
                  Option(
                    Result.Error(
                      s"$lab code too big: size [${e.sizeBytes}] is larger than allowed [${e.maxSizeBytes}]"
                    )
                  )
                )

              case err: smithy4s.http.UnknownErrorResponse =>
                if err.code == 502 then
                  result.set(
                    Option(
                      Result.Error(
                        "Looks like Fly.io killed the server - wait for a couple seconds and press the button again"
                      )
                    )
                  )

              case e: CompilationFailed =>
                val lab = e.which match
                  case AFTER  => "New"
                  case BEFORE => "Old"

                result.set(
                  Option(
                    Result
                      .Error(s"$lab code failed to compile:\n\n${e.errorOut}")
                  )
                )

              case other =>
                result.set(Option(Result.Error(other.toString())))
        )

    }

  val btn =
    button(
      "Check it",
      onClick.mapToStrict(Action.Submit) --> actionBus,
      cls := "bg-sky-700 text-lg font-bold p-2 text-white"
    )

  def codeMirrorTextArea(target: Var[String]) =
    textArea(
      cls := "w-full border-2 border-slate-400 p-2 text-md",
      onInput.mapToValue --> target,
      value <-- target,
      onMountCallback(el =>
        CodeMirror
          .fromTextArea(
            el.thisNode.ref,
            js.Dictionary(
              "value" -> target.now(),
              "lineNumbers" -> true,
              "mode" -> "text/x-scala"
            )
          )
          .on("change", value => target.set(value.getValue()))
      )
    )

  val basicLink =
    cls := "text-indigo-600 hover:no-underline underline"

  val app =
    div(
      cls := "content mx-auto w-8/12 bg-white/70 p-6 rounded-xl max-w-screen-lg flex flex-col gap-4",
      div(
        h1("Mimalyzer", cls := "text-6xl"),
        p(
          "Check whether your code change is ",
          a(
            href := "https://docs.scala-lang.org/overviews/core/binary-compatibility-for-library-authors.html",
            "binary compatible",
            basicLink
          ),
          " in Scala according to ",
          a(
            href := "https://github.com/lightbend-labs/mima",
            "MiMa",
            basicLink
          ),
          " and TASTy compatible in Scala 3 according to ",
          a(
            href := "https://github.com/scalacenter/tasty-mima",
            "TASTy-MiMa",
            basicLink
          ),
          cls := "font-italic text-sm"
        )
      ),
      div(
        cls := "w-full flex flex-row gap-4",
        div(
          cls := "flex flex-col gap-2 grow-0 w-6/12",
          h2("Scala code before", cls := "font-bold"),
          p(
            "This simulates the previous version of your library",
            cls := "text-md"
          ),
          codeMirrorTextArea(oldScalaCode)
        ),
        div(
          cls := "flex flex-col gap-2 grow-0 w-6/12",
          h2("Scala code after", cls := "font-bold"),
          p(
            "This simulates the next version of your library",
            cls := "text-md"
          ),
          codeMirrorTextArea(newScalaCode)
        )
      ),
      div(
        div(
          cls := "flex flex-row gap-8 text-2xl place-content-center",
          ScalaVersion.values.map: sv =>
            p(
              cls := "flex flex-row gap-2 m-2 border-2 border-slate-200 p-4 rounded-md cursor-pointer",
              cls("bg-rose-700 text-white") <-- scalaVersion.signal.map(
                _ == sv.value
              ),
              onClick.mapTo(sv.value) --> scalaVersion,
              input(
                tpe := "radio",
                nameAttr := "scala-version",
                value := sv.value,
                checked <-- scalaVersion.signal.map(_ == sv.value),
                onChange.mapToValue --> scalaVersion
              ),
              p("Scala ", sv.value)
            ),
        )
      ),
      btn,
      pre(
        cls := "whitespace-pre-line rounded-md text-2xl p-4",
        cls("bg-emerald-400") <-- result.signal.map:
          case Some(Result.NoProblems) => true
          case _                       => false,
        cls("bg-rose-400") <-- result.signal.map:
          case Some(Result.Problems(mima, tastyMima)) =>
            mima.isDefined || tastyMima.isDefined
          case _ => false,
        cls("bg-amber-400") <-- result.signal.map:
          case Some(Result.Error(_)) => true
          case _                     => false,
        child <-- result.signal.map:
          case Some(Result.NoProblems) =>
            span("Congratulations! This change is binary compatible")
          case Some(Result.Problems(mima, tastyMima)) =>
            div(
              cls := "flex flex-col gap-4",
              p(
                "DANGER!",
                cls := "font-bold"
              ),
              mima
                .map(problems =>
                  div(
                    p(
                      b(
                        "This change is not binary compatible according to MiMa:"
                      )
                    ),
                    p(problems)
                  )
                )
                .getOrElse("✅ This change is binary compatible"),
              tastyMima.map(problems =>
                div(
                  p(
                    b(
                      "This change is not TASTy compatible according to Tasty-MiMa:"
                    )
                  ),
                  p(problems)
                )
              )
            )
          case Some(Result.Error(s)) =>
            p(
              p(
                "Something is wrong",
                cls := "font-bold"
              ),
              s
            )

          case Some(Result.Waiting) => i("Please wait...")
          case None                 => "",
        display <-- result.signal.map(s =>
          if s.nonEmpty then display.block.value else display.none.value
        )
      ),
      div(
        cls := "text-sm",
        "Made by ",
        a(
          href := "https://blog.indoorvivants.com",
          "Anton Sviridov",
          basicLink
        ),
        " using ",
        a(href := "https://scala-js.org", "Scala.js", basicLink),
        " and ",
        a(
          href := "https://disneystreaming.github.io/smithy4s/",
          "Smithy4s",
          basicLink
        ),
        " from ",
        a(
          href := "https://github.com/indoorvivants/scala-cli-smithy4s-fullstack-template",
          "Fullstack Scala Template",
          basicLink
        ),
        p(
          "Contribute on ",
          a(
            href := "https://github.com/keynmol/mimalyzer",
            "Github",
            basicLink
          )
        )
      ),
      handleEvents,
      saveState
    )

  renderOnDomContentLoaded(dom.document.getElementById("content"), app)
end hello
