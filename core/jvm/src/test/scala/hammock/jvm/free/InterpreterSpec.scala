package hammock
package jvm
package free

import java.net.URI

import cats._
import cats.data.Kleisli
import cats.effect._
import hammock.free._
import org.apache.http.client.HttpClient
import org.apache.http.entity.StringEntity
import org.apache.http.message.{BasicHeader, BasicHttpResponse, BasicStatusLine}
import org.apache.http.{ProtocolVersion, HttpResponse => ApacheHttpResponse}
import org.mockito.Mockito._
import org.mockito.{Matchers => MM}
import org.scalatest._
import org.scalatest.mockito._

class InterpreterSpec extends WordSpec with MockitoSugar with BeforeAndAfter {
  import HttpResponse._
  import MM._
  import algebra._

  val client = mock[HttpClient]
  val interp = new Interpreter[IO](client)

  after {
    reset(client)
  }

  "Interpreter.trans" should {
    val methods = Seq(
      ("Options", (uri: Uri, headers: Map[String, String]) => Ops.options(uri, headers)),
      ("Get", (uri: Uri, headers: Map[String, String]) => Ops.get(uri, headers)),
      ("Head", (uri: Uri, headers: Map[String, String]) => Ops.head(uri, headers)),
      ("Post", (uri: Uri, headers: Map[String, String]) => Ops.post(uri, headers, None)),
      ("Put", (uri: Uri, headers: Map[String, String]) => Ops.put(uri, headers, None)),
      ("Delete", (uri: Uri, headers: Map[String, String]) => Ops.delete(uri, headers)),
      ("Trace", (uri: Uri, headers: Map[String, String]) => Ops.trace(uri, headers))
    ) map {
      case (method, operation) =>
        s"have the same result as transK.run(client) with $method requests" in {
          when(client.execute(any())).thenReturn(httpResponse)

          val op = operation(Uri(path = ""), Map())

          implicit def monadKleisli[A] = Kleisli.catsDataMonadForKleisli[IO, A]

          val k = op.foldMap[Kleisli[IO, HttpClient, ?]](interp.transK)

          val transkResult = k.run(client).unsafeRunSync
          val transResult  = (op foldMap interp.trans).unsafeRunSync

          assert(Eq[HttpResponse].eqv(transkResult, transResult))
        }
    }

    "create a correct Apache's HTTP request from HttpRequestF" in {
      val req = Get(
        HttpRequest(
          Uri.unsafeParse("http://localhost:8080"),
          Map(
            "header1" -> "value1",
            "header2" -> "value2"
          ),
          None))

      val apacheReq = interp.getApacheRequest(req).unsafeRunSync
      assert(apacheReq.getURI() == new URI("http://localhost:8080"))
      assert(apacheReq.getAllHeaders().length == 2)
      assert(
        apacheReq
          .getHeaders("header1")(0)
          .getValue == "value1")
      assert(
        apacheReq
          .getHeaders("header2")(0)
          .getValue == "value2")
    }

    "create a correct HttpResponse from Apache's HTTP response" in {
      when(client.execute(any())).thenReturn(httpResponse)

      val op = Ops.get(Uri(path = ""), Map())

      implicit def monadKleisli[A] = Kleisli.catsDataMonadForKleisli[IO, A]

      val k = op.foldMap[Kleisli[IO, HttpClient, ?]](interp.transK)

      val result = (op foldMap interp.trans).unsafeRunSync

      assert(result.status == Status.OK)
      assert(result.headers == Map())
      assert(result.entity.content == "content")
    }

  }

  private[this] def httpResponse: ApacheHttpResponse = {
    val resp   = new BasicHttpResponse(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, null))
    val entity = new StringEntity("content")

    resp.setEntity(entity)

    resp
  }

}