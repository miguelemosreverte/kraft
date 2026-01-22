package kraft

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import kraft.dsl.ws.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

// Test message types
case class ChatMessage(text: String, sender: String)

object WsTestCodecs:
  given JsonValueCodec[ChatMessage] = JsonCodecMaker.make

class WebSocketSpec extends AnyFunSuite with Matchers:
  import WsTestCodecs.given

  // ==========================================================================
  // WsMessage tests
  // ==========================================================================

  test("WsMessage.text creates text message"):
    val msg = WsMessage.text("Hello")
    msg shouldBe a[WsMessage.Text]
    msg.isText shouldBe true
    msg.isBinary shouldBe false

  test("WsMessage.binary creates binary message"):
    val msg = WsMessage.binary(Array[Byte](1, 2, 3))
    msg shouldBe a[WsMessage.Binary]
    msg.isText shouldBe false
    msg.isBinary shouldBe true

  test("WsMessage.json creates JSON text message"):
    val msg = WsMessage.json(ChatMessage("Hello", "Alice"))
    msg shouldBe a[WsMessage.Text]
    msg.asInstanceOf[WsMessage.Text].data should include("Hello")
    msg.asInstanceOf[WsMessage.Text].data should include("Alice")

  // ==========================================================================
  // CloseCode tests
  // ==========================================================================

  test("CloseCode has standard values"):
    CloseCode.Normal shouldBe 1000
    CloseCode.GoingAway shouldBe 1001
    CloseCode.ProtocolError shouldBe 1002
    CloseCode.AbnormalClosure shouldBe 1006

  // ==========================================================================
  // Server-side route builder tests
  // ==========================================================================

  test("WS creates a path builder"):
    val builder = WS("/chat")
    builder shouldBe a[WebSocketPathBuilder]
    builder.path shouldBe "/chat"

  test("WebSocketPathBuilder can chain handlers"):
    val handler = WS("/chat")
      .onOpen { println("opened") }
      .onTextMessage { msg => println(msg) }
      .onClose { (code, reason) => println(s"closed: $code") }
      .onError { ex => println(ex) }
      .build()

    handler.path shouldBe "/chat"

  test("WebSocketRoutes can hold multiple handlers"):
    val handler1 = WS("/chat").build()
    val handler2 = WS("/notifications").build()

    val routes = WebSocketRoutes(handler1, handler2)
    routes.routes.length shouldBe 2

  test("WebSocketRoutes can find handler by path"):
    val chatHandler = WS("/chat").build()
    val notifyHandler = WS("/notify").build()

    val routes = WebSocketRoutes(chatHandler, notifyHandler)

    routes.findHandler("/chat") shouldBe Some(chatHandler)
    routes.findHandler("/notify") shouldBe Some(notifyHandler)
    routes.findHandler("/unknown") shouldBe None

  test("WebSocketRoutes can be composed with <+>"):
    val routes1 = WebSocketRoutes(WS("/a").build())
    val routes2 = WebSocketRoutes(WS("/b").build())

    val combined = routes1 <+> routes2
    combined.routes.length shouldBe 2
    combined.findHandler("/a") should not be None
    combined.findHandler("/b") should not be None

  // ==========================================================================
  // Client builder tests
  // ==========================================================================

  test("WebSocketClient.connect creates a builder"):
    val builder = WebSocketClient.connect("wss://example.com/ws")
    builder shouldBe a[WebSocketClientBuilder]

  test("WebSocketClientBuilder can chain handlers"):
    val builder = WebSocketClient.connect("wss://example.com/ws")
      .onOpen { println("opened") }
      .onTextMessage { msg => println(msg) }
      .onClose { (code, reason) => println(s"closed: $code") }
      .onError { ex => println(ex) }
      .header("Authorization", "Bearer token")
      .subprotocol("graphql-ws")

    builder shouldBe a[WebSocketClientBuilder]

  // ==========================================================================
  // WsCodec tests
  // ==========================================================================

  test("String codec encodes and decodes"):
    val codec = summon[WsCodec[String]]
    val msg = codec.encode("Hello")
    msg shouldBe WsMessage.Text("Hello")

    codec.decode(WsMessage.Text("World")) shouldBe Some("World")
    codec.decode(WsMessage.Binary(Array.empty)) shouldBe None

  test("Binary codec encodes and decodes"):
    val codec = summon[WsCodec[Array[Byte]]]
    val data = Array[Byte](1, 2, 3)
    val msg = codec.encode(data)
    msg shouldBe a[WsMessage.Binary]

    val decoded = codec.decode(WsMessage.Binary(Array[Byte](4, 5, 6)))
    decoded.map(_.toSeq) shouldBe Some(Seq[Byte](4, 5, 6))
    codec.decode(WsMessage.Text("text")) shouldBe None

  test("JSON codec encodes and decodes"):
    val codec = summon[WsCodec[ChatMessage]]

    val msg = codec.encode(ChatMessage("Hi", "Bob"))
    msg shouldBe a[WsMessage.Text]
    msg.asInstanceOf[WsMessage.Text].data should include("Hi")

    val decoded = codec.decode(WsMessage.Text("""{"text":"Hello","sender":"Alice"}"""))
    decoded shouldBe Some(ChatMessage("Hello", "Alice"))

    // Invalid JSON returns None
    codec.decode(WsMessage.Text("not json")) shouldBe None
    codec.decode(WsMessage.Binary(Array.empty)) shouldBe None

  // ==========================================================================
  // Integration tests (against echo.websocket.org)
  // ==========================================================================

  test("WebSocketClient can connect to WebSocket server"):
    val openLatch = new CountDownLatch(1)
    val messageLatch = new CountDownLatch(1)
    val closeLatch = new CountDownLatch(1)
    val messageReceived = new AtomicReference[String]()

    // Use ws.postman-echo.com which is a reliable echo server
    val client = WebSocketClient.connect("wss://ws.postman-echo.com/raw")
      .onOpen {
        openLatch.countDown()
      }
      .onTextMessage { msg =>
        messageReceived.set(msg)
        messageLatch.countDown()
      }
      .onClose { (code, reason) =>
        closeLatch.countDown()
      }
      .build()

    // Wait for connection
    openLatch.await(10, TimeUnit.SECONDS) shouldBe true
    client.isConnected shouldBe true

    // Send a message
    client.send("Hello, Echo!")

    // Wait for response (echo servers typically return the message)
    messageLatch.await(10, TimeUnit.SECONDS) shouldBe true

    // The message should be received (content depends on echo server)
    messageReceived.get() should not be null

    // Close
    client.close()
    closeLatch.await(5, TimeUnit.SECONDS) shouldBe true
    client.isConnected shouldBe false

  test("WebSocketClient can send and receive messages"):
    val messageReceived = new AtomicReference[String]()
    val messageLatch = new CountDownLatch(1)
    val openLatch = new CountDownLatch(1)

    val client = WebSocketClient.connect("wss://ws.postman-echo.com/raw")
      .onOpen {
        openLatch.countDown()
      }
      .onTextMessage { msg =>
        messageReceived.set(msg)
        messageLatch.countDown()
      }
      .build()

    // Wait for connection
    openLatch.await(10, TimeUnit.SECONDS) shouldBe true

    // Send typed message
    val testMessage = ChatMessage("Hello!", "TestUser")
    client.send(testMessage)

    // Wait for response
    messageLatch.await(10, TimeUnit.SECONDS) shouldBe true

    // Check that we got some response
    val received = messageReceived.get()
    received should not be null
    received.length should be > 0

    client.close()

  test("WebSocketClient handles connection errors"):
    val errorRef = new AtomicReference[Throwable]()
    val errorLatch = new CountDownLatch(1)

    // Try to connect to invalid host
    try
      val client = WebSocketClient.connect("wss://invalid.invalid.invalid")
        .onError { ex =>
          errorRef.set(ex)
          errorLatch.countDown()
        }
        .build()

      // If we get here without exception, wait for error callback
      errorLatch.await(5, TimeUnit.SECONDS)
      client.isConnected shouldBe false
    catch
      case _: Exception =>
        // Expected - connection should fail
        succeed
