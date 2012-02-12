(ns wol.websockets.server
  (:use
   [clj-etl-utils.lang-utils :only [raise]])
  (:import
   [io.netty.bootstrap ServerBootstrap]
   [io.netty.channel.socket.nio NioServerSocketChannelFactory]  

   [io.netty.channel ChannelPipelineFactory Channels SimpleChannelUpstreamHandler ChannelFutureListener]
   [io.netty.handler.codec.http
    HttpChunkAggregator
    HttpRequestDecoder
    HttpResponseEncoder
    DefaultHttpResponse]
   [io.netty.buffer ChannelBuffers]
   [io.netty.handler.codec.http HttpMethod HttpVersion HttpResponseStatus HttpRequest ]
   [io.netty.handler.codec.http.websocketx
    CloseWebSocketFrame    
    PingWebSocketFrame
    PongWebSocketFrame
    TextWebSocketFrame
    WebSocketFrame
    WebSocketServerHandshaker
    WebSocketServerHandshakerFactory]
   
   [java.nio.charset Charset]
   [java.util.concurrent Executors]
   [java.net InetSocketAddress]))



(def *ws-uri* "ws://localhost:8081/commands")
(def ^{:dynamic true } *web-socket* nil)
(defonce *web-sockets* (atom #{}))


(defn make-handler [{:keys [message-received channel-connected channel-disconnected post-ws-handshake]}]
  
  (let [handshaker               (atom nil)
        send-http-response       (fn [ctx req res]
                                   (let [channel-future (-> (.getChannel ctx) (.write res))]
                                     (.addListener channel-future ChannelFutureListener/CLOSE)))
        handle-http-event        (fn [ctx req]
                                   (cond  (not= (.getMethod req) HttpMethod/GET)
                                          (send-http-response ctx req (DefaultHttpResponse. HttpVersion/HTTP_1_1
                                                                        HttpResponseStatus/FORBIDDEN))

                                          (-> (.getUri req) (.equals "/favicon.ico"))
                                          (send-http-response ctx req (DefaultHttpResponse. HttpVersion/HTTP_1_1
                                                                        HttpResponseStatus/NOT_FOUND))

                                          :else
                                          (let [ws-factory (WebSocketServerHandshakerFactory. *ws-uri* nil false) ]
                                            (reset! handshaker (.newHandshaker ws-factory req))
                                            (if (nil? @handshaker)
                                              (.sendUnsupportedWebSocketVersionResponse ws-factory (.getChannel ctx))
                                              (let [handshake-future (.handshake @handshaker (.getChannel ctx) req)]
                                                (and post-ws-handshake 
                                                     (.addListener handshake-future post-ws-handshake)))))))        
        handle-web-socket-event   (fn [ctx frame]
                                    (if  (instance?  CloseWebSocketFrame frame)
                                      (do
                                        (.close @handshaker (.getChannel ctx) frame))
                                      (let [text (.getText frame)]
                                        (if message-received
                                          (binding [*web-socket* (.getChannel ctx)]
                                            (message-received text))))))]
    
    (proxy [SimpleChannelUpstreamHandler] []      
      (channelConnected [ctx e]
        (swap! *web-sockets* conj (.getChannel ctx))
        (if channel-connected
          (channel-connected ctx e)))      
      (messageReceived [ctx e]      
        (let [msg (.getMessage e)]
          (cond (instance?  WebSocketFrame msg)                             
                (handle-web-socket-event ctx msg)

                (instance?  HttpRequest msg)
                (handle-http-event ctx msg)

                :else
                (raise (format "Unsupported message event type %s "  msg)))))

      (channelDisconnected [ctx e]
        (swap! *web-sockets* disj (.getChannel ctx))
        (if channel-disconnected
          (channel-disconnected ctx e)))
      
      (exceptionCaught [ctx ex]
        (raise (.getCause ex) " rethrowing upstream exception")))))

(defn ws-respond [resp]
  (.write *web-socket* (TextWebSocketFrame. resp)))

(defn ws-broadcast-others [resp]
  (let [response (TextWebSocketFrame. resp)] 
   (doseq [ws (filter #(not (= %1 *web-socket*)) @*web-sockets*)]
     (.write ws response))))

(defn ws-broadcast-all [resp]
  (let [response (TextWebSocketFrame. resp) ]
    (doseq [ws @*web-sockets*]
      (.write ws response))))

(comment

  (def *server* (start-netty-server))
  (.close *server*)
  (.write (first ((get  (. *handler* __getClojureFnMappings)  "getChannels") nil )) (TextWebSocketFrame.  "right back at ya brother"))
  (.isOpen (first ((get  (. *handler* __getClojureFnMappings)  "getChannels") nil )))
  
  )



(defn start-netty-server [config]
  (let [ch-factory (NioServerSocketChannelFactory. (Executors/newCachedThreadPool)
                                                   (Executors/newCachedThreadPool))
        bootstrap       (ServerBootstrap. ch-factory)
        pl-factory      (reify ChannelPipelineFactory
                          (getPipeline [this]
                            (doto (Channels/pipeline)
                              (.addLast "decode"     (HttpRequestDecoder.))
                              (.addLast "aggregator" (HttpChunkAggregator. 65536))
                              (.addLast "encode"     (HttpResponseEncoder.))
                              (.addLast "handler"    (make-handler config)))))]
    (.setPipelineFactory bootstrap pl-factory)
    (.setOption bootstrap "child.tcpNoDelay" true)
    (.setOption bootstrap "child.keepAlive" true)
    (.bind bootstrap (InetSocketAddress. (:port config)) )))

 