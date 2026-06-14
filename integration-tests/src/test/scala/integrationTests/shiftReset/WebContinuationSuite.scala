package integrationTests.shiftReset

import continuations.*

class WebContinuationSuite extends munit.FunSuite:

  test("shift can model a web form continuation") {
    var submit: String => String = null

    val form = reset {
      val name = shift[String, String] { k =>
        submit = k
        "<form name=user></form>"
      }
      s"Hello, $name"
    }

    assertEquals(form, "<form name=user></form>")
    assertEquals(submit("Alice"), "Hello, Alice")
  }
