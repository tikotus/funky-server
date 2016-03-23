(ns funky-server.core  
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json])
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

(defn close-socket-client [{:keys [in out socket] :as this}]
  (when (socket-open? socket)
    (log/info "Closing async socket")
    (.shutdownInput socket)
    (.shutdownOutput socket)
    (.close socket)
    (async/close! in)
    (async/close! out))
  (assoc this :socket nil :in nil :out nil))

(defn- init-async-socket [socket]
  (log/info "Initing new async socket")
  (let [in (io/reader socket)
        out (io/writer socket)
        in-ch (async/chan 8 (map #(json/read-str % :key-fn keyword)) #(log/error "Error in received message" %))
        out-ch (async/chan 8 (map json/write-str) #(log/error "Error in sent message" %))
        public-socket {:socket socket :in in-ch :out out-ch}]
    
    (async/go-loop [] 
      (let [line (socket-read-line-or-nil socket in)]
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
    
    (log/info "New async socket opened")
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
        (do
          (try
            (log/info "Waiting for connection")
            (async/>! conns (init-async-socket (.accept java-server)))
            (catch Exception e
              (log/error e)
              (stop-socket-server public-server)))
          (recur))
        (stop-socket-server public-server)))
    public-server))





;; socket = require("socket");tcp = socket.tcp();tcp:connect("127.0.0.1", 8888);tcp:settimeout(0);
;; tcp:send("{\"id\":\"foo\", \"max-players\":4, \"step-time\":1000}\n")
;; tcp:send("{\"msg\":\"foobar\"}\n")
;; print(tcp:receive()) -- prints "New player..."
;; print(tcp:receive()) -- prints "...msg:foobar..."
;; print(tcp:receive()) -- prints "...timeout..."

(defn start-game [max-players id step-time]
  (log/info "Starting game with id" id ", max-players" max-players ", step-time" step-time)
  (let [in (async/chan)
        out (async/chan)
        out-mult (async/mult out)
        step (atom 0)]
    
    (async/pipeline 1 out (map #(assoc % :step (+ @step 2))) in)
    
    (async/go-loop []
      (async/<! (async/timeout step-time))
      (swap! step inc)
      (async/>! out {:msg "lock" :step (+ @step 1)})
      (recur))
    
    {:in in 
     :out-mult out-mult 
     :players (atom 0)
     :max-players max-players 
     :id id 
     :close #(do (async/close! out) (async/close! in))}))


(defn choose-game [player-socket games]
  (log/info "Choosing game")
  (try 
    (let [{:keys [id max-players step-time]} (async/<!! (:in player-socket))]
      (log/info "Looking for game with id" id "in games" @games "and found" (first (filter #(= (:id %) id) @games)))
      (log/info id (:id (first @games)) (= id (:id (first @games))))
      (or (first (filter #(= (:id %) id) @games))
          (let [game (start-game max-players id step-time)]
            (swap! games conj game)
            game)))
    (catch Exception e
      (log/error e))))

(defn add-player [game player-socket]
  (async/pipe (:in player-socket) (:in game) false)
  (async/tap (:out-mult game) (:out player-socket))
  (swap! (:players game) inc)
  (log/info "players" @(:players game))
  (async/>!! (:in game) {:msg "New player joined" :players @(:players game)})
  game)
  
(defn start-lockstep-server [port]
  (let [server (socket-server port)
        games (atom [])]
    (async/go-loop []
      (when-let [socket (async/<! (:connections server))]
        (log/info "Accepted connection")
        (let [game (choose-game socket games)]
          (log/info "Chose game" (:id game) "Now having games" (count @games))
          (add-player game socket))
        (recur)))
    server))



(def server (start-lockstep-server 8888))












(defn start-echo-server [port]
  (let [server (socket-server port)]
    (async/go-loop []
      (when-let [socket (async/<! (:connections server))]
        (log/info "Accepted connection")
        (async/go-loop []
          (when-let [msg (async/<! (:in socket))]
            (log/info "Received message" msg)
            (async/>! (:out socket) msg)
            (recur)))
        (recur)))
    server))







(def c (async/chan))
(def mult-c (async/mult c))
(async/close! c)





(defn receive
  [socket]
  (try
    (let [msg (.readLine (io/reader socket))]
      (log/info "Received message" msg)
      msg)
    (catch SocketException e
      (log/error e))))

(defn send
  [socket msg]
  (try 
    (let [writer (io/writer socket)]
      (.write writer msg)
      (.flush writer))
    (catch SocketException e
      (log/error e))))

(defn serve [port handler]
  (let [running (atom true)]
    (future
      (while @running 
        (with-open [server-sock (ServerSocket. port)
                    sock (.accept server-sock)]
          (log/info "Opened socket")
          (let [msg-in (receive sock)
                msg-out (try (handler msg-in) (catch Exception e (log/error e)))]
            (log/info "Got message" msg-in)
            (send sock msg-out)))))
    running))



(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))
