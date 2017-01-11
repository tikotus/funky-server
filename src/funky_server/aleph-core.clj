(require '[aleph.http :as http])
(require '[aleph.tcp :as tcp])
(require '[manifold.stream :as s])


(defn- init-socket [stream]
  (log/info "Initing new async socket")
  (let [in-ch (async/chan 8 (map #(json/read-str (String. %) :key-fn keyword)) #(log/error "Error in received message" %))
        out-ch (async/chan 8 (map json/write-str) #(log/error "Error in sent message" %))
        public-socket {:in in-ch :out out-ch}]
    
    (s/connect stream in-ch)
    (s/connect out-ch stream)

    (log/info "New async socket opened")
    public-socket))


(defn socket-server [port]
  (let [connections (async/chan 50 (map init-socket))
  		aleph-server (tcp/start-server (fn [s _] (async/>!! connections s)) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :connections connections :server aleph-server}))


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
;;      (async/>! out {:msg "lock" :step (+ @step 1)})
      (recur))
    
    {:in in 
     :out-mult out-mult 
     :players 0
     :max-players max-players 
     :id id 
     :close #(do (async/close! out) (async/close! in))}))


(defn add-player [player-socket game]
  (log/info "Add player to game" (:id game) "with" (:players game) "players")
  (async/pipe (:in player-socket) (:in game) false)
  (async/tap (:out-mult game) (:out player-socket))
  (async/>!! (:in game) {:msg "New player joined" :players (inc (:players game))})
  (update game :players inc))


(defn indices [pred coll]
   (keep-indexed #(when (pred %2) %1) coll))

(defn join-game [games player-socket]
  (let [{:keys [id max-players step-time]} (async/<!! (:in player-socket))]
    (if-let [i (first (indices #(= (:id %) id) games))]
      (assoc games i (add-player player-socket (nth games i)))
      (->> (start-game max-players id step-time)
           (add-player player-socket)
           (conj games)))))

  
(defn start-lockstep-server [server]
  (async/reduce join-game [] (:connections server)))


(defn -main []
  (async/<!! (start-lockstep-server (socket-server 8888))))


(.close (:server server))
(def server (socket-server 8888))
(start-lockstep-server server)

