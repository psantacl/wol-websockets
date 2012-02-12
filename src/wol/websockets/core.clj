(ns wol.websockets.core
  (:require
   [wol.websockets.server :as server])
  (:use
   [wol.websockets.server :only [send-ws-message]])
  (:import
   [io.netty.handler.codec.http.websocketx TextWebSocketFrame]))

(def channels (atom #{}))

(defn channel-connected [ctx channel-event]
  (println "channel connected")
  (swap! channels conj (.getChannel ctx)))

(defn channel-disconnected [ctx channel-event]
  (println (format  "channel disconnected : %s" (.getChannel ctx)))
  (swap! channels disj (.getChannel ctx)))

(defn message-received  [req]
  (println (format "got %s " req))
  (println "sending back 'chicken world'")
  (send-ws-message "chickdn world"))


(comment
  (def server (server/start-netty-server
               {:channel-connected channel-connected
                :channel-disconnected channel-disconnected
                :message-received message-received
                :port 8081}))
  (.close server)

  channels
  (.write (first @channels) (TextWebSocketFrame. "howdy"))
  (doseq [channel @channels]
    (.write channel  (TextWebSocketFrame. "welcome to the island")))

  (require 'clojure.contrib.classpath)
  (clojure.contrib.classpath/classpath)
  
  )
