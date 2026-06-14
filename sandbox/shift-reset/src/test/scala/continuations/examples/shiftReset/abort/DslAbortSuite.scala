package continuations.examples.shiftReset.abort

import continuations.*

class DslAbortSuite extends munit.FunSuite:

  private def page(loggedIn: Boolean): String =
    reset {
      if loggedIn then "<p>Hello, user</p>"
      else shift[String, String](_ => "<p class=error>login required</p>")
    }

  test("logged in users see content") {
    assertEquals(page(loggedIn = true), "<p>Hello, user</p>")
  }

  test("logged out users abort to login message") {
    assertEquals(page(loggedIn = false), "<p class=error>login required</p>")
  }
