(ns funky-server.core  
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log])
  (:import [java.net ServerSocket SocketException InetAddress InetSocketAddress]
           [java.io BufferedReader BufferedWriter]))

(def system-newline ;; This is in clojure.core but marked private.
  (System/getProperty "line.separator"))

(defn- socket-open? [socket]
  (not (or (.isClosed socket) (.isInputShutdown socket) (.isOutputShutdown socket))))

(defn- socket-read-line-or-nil [socket in]
  (when (socket-open? socket)
    (try (.readLine in)
      (catch SocketException e (log/error e)))))

(defn- socket-write-line [socket out line]
  (if (socket-open? socket)
    (try
      (.write out (str line system-newline))
      (when *flush-on-newline* (.flush out))
      true
      (catch SocketException e
        (log/error e)
        false))
    false))

(defn close-socket-client [{:keys [in out socket address] :as this}]
  (log/info "Closing async socket on address" address)
  (.shutdownInput socket)
  (.shutdownOutput socket)
  (.close socket)
  (async/close! in)
  (async/close! out)
  (assoc this :socket nil :in nil :out nil))

(defn- init-async-socket [socket address]
  (let [in (io/reader socket)
        out (io/writer socket)
        in-ch (async/chan)
        out-ch (async/chan)
        public-socket {:socket socket :in in-ch :out out-ch}]
    
    (async/go-loop [] 
      (let [line (socket-read-line-or-nil)]
        (if-not line
          (close-socket-client public-socket)
          (do
            (async/>! in-ch line)
            (recur)))))
    
    (async/go-loop []
      (let [line (and (socket-open? socket) (async/<! out-ch))]
        (if-not (socket-write-line socket out line)
          (close-socket-client public-socket)
          (recur))))
    
    (log/info "New async socket opened at address" address)
    public-socket))


(defn server-running? [server]
  (and server (not (.isClosed (:server server)))))

(defn stop-socket-server [server]
  (when (server-running? server)
    (log/info "Stopping server on port" (:port server))
    (async/close! (:connections server))
    (.close (:server server))
    (assoc server :connections nil :server nil)))

(defn socket-server [port]
  (let [java-server (ServerSocket. port 50)
        conns (async/chan 50)
        public-server {:port port :connections conns :server java-server}]
    (log/info "Starting async server at port" port)
    
    (async/go-loop []
      (if (and (not (.isClosed java-server)) (.isBound java-server))
        (try
          (async/>! conns (init-async-socket (.accept java-server)))
          (catch SocketException e
            (log/error e)
            (stop-socket-server public-server)))
        (stop-socket-server public-server)))
    public-server))






















(defn receive
  [socket]
  (.readLine (io/reader socket)))

(defn send
  [socket msg]
  (let [writer (io/writer socket)]
    (.write writer msg)
    (.flush writer)))

(defn serve [port handler]
  (let [running (atom true)]
    (future
      (while @running 
        (with-open [server-sock (ServerSocket. port)
                    sock (.accept server-sock)]
          (let [msg-in (receive sock)
                msg-out (handler msg-in)]
            (send sock msg-out)))))
    running))



(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))
