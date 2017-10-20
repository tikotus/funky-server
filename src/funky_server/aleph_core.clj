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

(defn- init-player [stream]
  (log/info "Initing new player")
  (let [last-msg-time (atom (l/local-now))
        handle-message #(do (reset! last-msg-time (l/local-now)) (json/read-str % :key-fn keyword))
        in-ch (async/chan (async/sliding-buffer 64) (map handle-message) #(log/error "Error in received message" %))
        out-ch (async/chan (async/dropping-buffer 128) (map #(json/write-str %)) #(log/error "Error in sent message" %))
        player {:in-local (async/chan) :in in-ch :out out-ch :last-msg-time last-msg-time}]
    
    (s/connect stream in-ch)
    (s/connect out-ch stream)

    (async/go-loop []
      (async/<! (async/timeout 1000))
      (if (> (t/in-millis (t/interval @last-msg-time (l/local-now))) 30000)
        (when-not (s/closed? stream) (s/close! stream))
        (recur)))

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
        nil)))

(defn wait-for-disconnect [stream player]
  (log/info "wait for disconnect")
  (let [done (async/chan)]
    (async/go
      (s/on-closed stream #(async/put! done (assoc player :disconnected? true)))
      (async/<! done))))


(defn handshake [player]
  (let [id (new-uuid)
        player (assoc player :id id)]
    (async/go
      (async/>! (:out player) {:msg "Welcome!" :id id})
      (loop []
        (let [msg (async/<! (:in player))]
          (if (nil? msg)
            nil
            (if (every? msg #{:gameType :maxPlayers :stepTime})
              (assoc player :game-info (clojure.set/rename-keys msg {:gameType :game-type :maxPlayers :max-players :stepTime :step-time}))
              (recur))))))))

(defn stream-write [out value]
  (async/go
    (async/>! out value)
    value))

(def protocol (gloss/string :utf-8 :delimiters ["\n"]))

(defn wrap-duplex-stream [protocol s]
  (log/info "wrap duplex stream")
  (let [out (s/stream)]
    (s/connect
      (s/map #(gloss.io/encode protocol %) out)
      s)
    (s/splice
      out
      (gloss.io/decode-stream s protocol))))

(defn handle-new-connection [stream info players]
  (log/info "new connection" info)
  (async/go 
    (log/info "start go block")
    (some->> stream
             (wrap-duplex-stream protocol)
             init-player
             (handshake) (async/<!)
             (stream-write players) (async/<!)
             (wait-for-disconnect stream) (async/<!)
             (stream-write players) (async/<!)
             (log/info "Disconnected player" info))
    (log/info "end connection" info)))


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

(defn start-ticker [step step-time out done join-ch]
  (let [start-time (l/local-now)]
    (async/go-loop []
      (let [run-time (t/in-millis (t/interval start-time (l/local-now)))
            step-time (- step-time (mod run-time step-time))]
        (when (-> [done (async/timeout step-time)] async/alts! second (not= done))
          ;;(log/info (str "step " @step " " (l/local-now)))
          (swap! step inc)
          (let [lock-msg {:lock (dec @step)}
                join-msg (async/poll! join-ch)]
            (async/>! out (if (nil? join-msg) [lock-msg] [lock-msg (assoc join-msg :step (dec @step))])))
          (recur))))))

(defn choose-topic [msg]
  (if (contains? msg :lock)
    :lock
    (case (:msg msg) 
      "sync" :sync 
      "join" :join 
      :other)))

(defn start-game [type max-players step-time]
  (log/info "Starting game with type" type ", max-players" max-players ", step-time" step-time)
  (let [in (async/chan)
        out (async/chan 1 cat)
        out-pub (async/pub out choose-topic)
        join-ch (async/chan 8)
        step (atom 0)
        done (async/chan)]
    
    (if (zero? step-time)
      (async/pipeline 1 out (filter #(-> % :alive nil?)) in)
      (let [alive-filter (filter #(-> % :alive nil?))
            add-step-map (map #(assoc % :step @step))
            add-vec-map (map #(identity [%]))]
        (async/pipeline 1 out (comp alive-filter add-step-map add-vec-map) in)
        (start-ticker step step-time out done join-ch)))

    {:in in
     :join-ch join-ch
     :out-pub out-pub
     :players #{}
     :synced-players (atom [])
     :next-player-id 0
     :max-players max-players 
     :type type
     :done done
     :seed (rand-int 500000) ;; something big but avoids overflow
     :close #(do (async/close! out) (async/close! in))}))


(defn active? [player]
  (< (t/in-millis (t/interval @(:last-msg-time player) (l/local-now))) 
     2000))

(defn pick-syncer [game]
  (let [active (filter active? @(:synced-players game))]
    (if (> (count active) 0)
      (:id (rand-nth active))
      nil)))

(defn request-sync [player game]
  (let [sync-chan (async/chan (async/sliding-buffer 1))
        lock-chan (async/chan)]
    (async/go
      (async/sub (:out-pub game) :lock lock-chan)
      (async/<! lock-chan) ; Read one lock before syncing to ensure client has all messages for the step
      (async/unsub (:out-pub game) :lock lock-chan)
      (async/sub (:out-pub game) :sync sync-chan)
      (async/>! (:join-ch game) {:msg "join" :syncer (pick-syncer game)})
      (async/>! (:out player) (async/<! sync-chan))
      (async/unsub (:out-pub game) :sync sync-chan))))

(defn request-sync-loop [player game]
  (let [sync-chan (async/chan (async/sliding-buffer 1))]
    (async/sub (:out-pub game) :sync sync-chan)
    (async/go-loop []
      (async/>! (:in game) {:msg "join" :syncer (rand-nth (seq @(:synced-players game)))})
      (let [[val ch] (async/alts! [sync-chan (async/timeout 2000)])]
        (if (identical? sync-chan ch)
          (do (async/>! (:out player) val)
              (async/unsub (:out-pub game) :sync sync-chan)
              "ok")
          (recur))))))

(defn add-player [player game]
  (let [playerId (:next-player-id game)
        newGame? (empty? (:players game))]
    (log/info "Add player to game" (:type game) "with players" (:players game))
    (async/go
      (async/sub (:out-pub game) :other (:out player))
      (async/sub (:out-pub game) :lock (:out player))
      (async/>! (:out player) {:join true :newGame newGame? :playerId playerId :seed (:seed game)})
      (async/pipeline 1 (:in game) (map #(assoc % :playerId playerId)) (async/merge [(:in player) (:in-local player)]) false)
      (when-not newGame? (async/<! (request-sync player game)))
      (swap! (:synced-players game) #(conj % player))
      (async/sub (:out-pub game) :join (:out player))
      (log/info "new player joined"))
    (-> game
        (update :players conj (:id player))
        (update :next-player-id inc))))

(defn remove-player [player game]
  (if (contains? (:players game) (:id player))
    (do 
      (async/put! (:in-local player) {:disconnected (:id player)})
      (log/info "Removed player from game" (:type game) "Remaining players" (:players game))
      (swap! (:synced-players game) (fn [players] (filter #(not= (:id %) (:id player)) players)))
      (log/info "Remaining synced-players" (count @(:synced-players game)))
      (update game :players disj (:id player)))
    game))

(defn indices [pred coll]
   (keep-indexed #(when (pred %2) %1) coll))

(defn valid-game? [game-info game]
  (and (= (:type game) (:game-type game-info)) 
       (-> game :players count (< (:max-players game-info)))
       (not (nil? (pick-syncer game)))))

(defn pick-game-index [game-info games]
  (first (indices #(valid-game? game-info %) games)))

(defn join-game [games player]
  (let [game-info (:game-info player)
        {:keys [game-type max-players step-time]} game-info]
    (if-let [i (pick-game-index game-info games)]
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
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (log/error ex "Uncaught exception on" (.getName thread)))))
  (tcp/start-server echo-handler {:port 9120})
  (log/info "done" (async/<!! (start-lockstep-server (socket-server 9121) (websocket-server 9122))))
  (log/info "quit"))
