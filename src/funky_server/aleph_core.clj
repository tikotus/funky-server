(ns funky-server.aleph-core  
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [aleph.http :as http]
            [gloss.core :as gloss]
            [gloss.io :as gloss.io]
            [aleph.tcp :as tcp]
            [clj-time.local :as l]
            [clj-time.core :as t]
            [manifold.stream :as s]))

(defn new-uuid 
  "Retrieve a type 4 (pseudo randomly generated) UUID. The UUID is generated using a cryptographically strong pseudo random number generator." 
  []
  (str (java.util.UUID/randomUUID)))

(def system-newline ;; This is in clojure.core but marked private.
  (System/getProperty "line.separator"))

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn- init-player [stream]
  (log/info "Initing new player")
  (let [last-msg-time (atom (l/local-now))
        handle-message #(do (reset! last-msg-time (l/local-now)) (json/read-str % :key-fn keyword))
        in-ch (async/chan (async/sliding-buffer 64) (map handle-message) #(log/error "Error in received message" %))
        out-ch (async/chan 64 (map #(json/write-str %)) #(log/error "Error in sent message" %))
        player {:in in-ch :out out-ch}]
    
    (s/connect stream in-ch)
    (s/connect out-ch stream)

    (future (loop []
      (Thread/sleep 1000)
      (if (> (t/in-millis (t/interval @last-msg-time (l/local-now))) 10000)
        (when-not (s/closed? stream) (s/close! stream))
        (recur))))

    (log/info "New player initialized")
    player))

(defn init-websocket [req]
  (log/info "Init websocket")
  (if-let [socket (try
                    @(http/websocket-connection req {:headers {:Sec-WebSocket-Protocol "binary"}})
                    (catch Exception e nil))]
    socket
    (do 
        (log/info "Not websocket")
        non-websocket-request)))

(defn wait-for-disconnect [stream player]
  (log/info "wait for disconnect")
  (let [done (async/chan)]
    (s/on-closed stream #(async/>!! done (assoc player :disconnected? true)))
    (async/<!! done)))

(defn handshake [player]
  (let [id (new-uuid)
        player (assoc player :id id)]
    (async/>!! (:out player) {:msg "Welcome!" :id id})
    (loop []
      (when-let [msg (async/<!! (:in player))]
        (if (every? msg #{:gameType :maxPlayers :stepTime})
          (assoc player :game-info (clojure.set/rename-keys msg {:gameType :game-type :maxPlayers :max-players :stepTime :step-time}))
          (recur))))))

(when "bar" "foo")
      
(defn stream-write [out value]
  (async/>!! out value)
  value)

(def protocol (gloss/string :utf-8 :delimiters ["\n"]))

(defn wrap-duplex-stream
  [protocol s]
  (let [out (s/stream)]
    (s/connect
      (s/map #(gloss.io/encode protocol %) out)
      s)
    (s/splice
      out
      (gloss.io/decode-stream s protocol))))

(defn handle-new-connection [stream info players]
  (log/info "new connection" info)
  (async/go (some->> stream
                 (wrap-duplex-stream protocol)
                 init-player
                 handshake
                 (stream-write players)
                 (wait-for-disconnect stream)
                 (stream-write players)
                 (log/info "Disconnected player" info))))


(defn socket-server [port]
  (let [players (async/chan)
  		  aleph-server (tcp/start-server #(handle-new-connection %1 %2 players) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :players players :server aleph-server}))

(def protocol (gloss/string :utf-8 :delimiters ["\n"]))

(defn websocket-server [port]
  (let [players (async/chan)
        aleph-server (http/start-server #(handle-new-connection (init-websocket %1) "websocket" players) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :players players :server aleph-server}))

(defn start-ticker [step step-time out done]
  (let [start-time (l/local-now)]
    (async/go-loop []
      (let [run-time (t/in-millis (t/interval start-time (l/local-now)))
            step-time (- step-time (mod run-time step-time))]
        (when (-> [done (async/timeout step-time)] async/alts! second (not= done))
          ;;(log/info (str "step " @step " " (l/local-now)))
          (swap! step inc)
          (async/>! out {:lock (dec @step)})
          (recur))))))

(defn start-game [type max-players step-time]
  (log/info "Starting game with type" type ", max-players" max-players ", step-time" step-time)
  (let [in (async/chan)
        out (async/chan)
        out-mult (async/mult out)
        step (atom 0)
        done (async/chan)]
    
    (async/pipeline 1 out (map #(assoc % :step @step)) in)
    (start-ticker step step-time out done)

    {:in in 
     :out-mult out-mult 
     :players #{}
     :next-player-id 0
     :max-players max-players 
     :type type
     :done done
     :close #(do (async/close! out) (async/close! in))}))


(defn add-player [player game]
  (log/info "Add player to game" (:type game) "with players" (:players game))
  (async/pipe (:in player) (:in game) false)
  (async/>!! (:out player) {:newGame (empty? (:players game)) :playerId (:next-player-id game)})
  (async/tap (:out-mult game) (:out player))
  (async/>!! (:in game) {:msg "New player joined" :id (:id player)})
  (-> game
      (update :players conj (:id player))
      (update :next-player-id inc)))

(defn remove-player [player game]
  (when (contains? (:players game) (:id player))
    (async/>!! (:in game) {:msg "Player disconnected" :disconnected (:id player)})
    (log/info "Removed player from game" (:type game) "with players" (:players game))
    (update game :players disj (:id player))))

(defn indices [pred coll]
   (keep-indexed #(when (pred %2) %1) coll))

(defn join-game [games player]
  (let [game-info (:game-info player)
        {:keys [game-type max-players step-time]} game-info]
    (if-let [i (first (indices #(= (:type %) game-type) games))]
      (assoc games i (add-player player (nth games i)))
      (->> (start-game game-type max-players step-time)
           (add-player player)
           (conj games)))))

(defn quit-game [games player]
  (let [games (map #(remove-player player %) games)
        { emptied true existing false } (group-by #(empty? (:players %)) games)]
    (doall (map #(async/close! (:done %)) emptied))
    (or existing [])))

(defn join-or-quit-game [games player]
  (if (:disconnected? player)
    (quit-game games player)
    (join-game games player)))

(defn start-lockstep-server [socket-server websocket-server]
  (let [players (async/chan 1)]
    (async/pipe (:players websocket-server) players)
    (async/pipe (:players socket-server) players)
    (async/reduce join-or-quit-game [] players)))

(defn echo-handler [s info]
  (s/connect s s))

(defn -main []
  (tcp/start-server echo-handler {:port 10001})
  (async/<!! (start-lockstep-server (socket-server 8888) (websocket-server 8889))))
