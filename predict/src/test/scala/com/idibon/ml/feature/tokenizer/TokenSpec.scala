package com.idibon.ml.feature.tokenizer

import org.scalatest.{Matchers, FunSpec}

class TokenSpec extends FunSpec with Matchers {

  describe ("of") {

    it("should identify BMP Whitespace") {
      // http://www.fileformat.info/info/unicode/category/Zs/list.htm
      for (x <- List("\r\n \t", "\u00a0", "\u202f", "\u2000",
        "\u2001", "\u3000", "\u2009", "\u205f"))
        Tag.of(x, 0) shouldBe Tag.Whitespace
    }

    it("should identify BMP Punctuation") {
      // most of these taken from unicode Punctuation, other:
      // http://www.fileformat.info/info/unicode/category/Po/list.htm
      for (x <- List(".,?!#-/\\@", "\uff0e", "\uff02", "\u0701",
        "\u061f", "\u0df4", "\u1801", "\u00a7", "\u055f",
        "\u060c", "\u2014")) {
        Tag.of(x, 0) shouldBe Tag.Punctuation
      }
    }

    it("should identify mathematical symbols") {
      // FIXME: add a new category for symbols?
      for (x <- List("\u00f7", "+", "\u222b"))
        Tag.of(x, 0) shouldBe Tag.Word
    }

    it("should use the reported rule status, if present") {
      Tag.of(":)", 0) shouldBe Tag.Punctuation
      Tag.of(":)", Tag.ruleStatus(Tag.Word)) shouldBe Tag.Word
    }

    it("should identify punctuation from the supplementary planes") {
      for (x <- List("\ud805\udf3d", "\ud836\ude8a"))
        Tag.of(x, 0) shouldBe Tag.Punctuation
    }

    it("should treat combining marks with punctuation as punctuation") {
      Tag.of("(.\u0321\u0301)\u030a\ud802\udf39\u030a", 0) shouldBe Tag.Punctuation
    }

    it("should treat combining marks with whitespace as non-whitespace") {
      /* some emoticons (like Lenny face) use combining marks
       * over whitespace. these should be detected as non-whitespace */
      Tag.of(" \u0361", 0) shouldBe Tag.Word
    }
  }

  describe("get") {

    it("Token.get should output the full feature") {
      val tokenHello = new Token("Hello", Tag.Word, 0, 0)
      val tokenWorld = new Token("World", Tag.Word, 0, 0)

      tokenHello.get shouldBe Token("Hello", Tag.Word, 0, 0)
      tokenWorld.get shouldBe Token("World", Tag.Word, 0, 0)
    }

    it("Token.getHumanReadableString should output human-readable strings") {
      val tokenHello = new Token("Hello", Tag.Word, 0, 0)
      val tokenWorld = new Token("World", Tag.Word, 0, 0)

      tokenHello.getHumanReadableString shouldBe Some("Hello (0, 0)")
      tokenWorld.getHumanReadableString shouldBe Some("World (0, 0)")
    }
  }
}
