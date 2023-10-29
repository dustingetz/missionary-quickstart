(ns quickstart
  "Missionary quickstart, for 10x devs with ADHD"
  (:require [hyperfiddle.rcf :refer [tests % tap with]]
            [missionary.core :as m]))


; Example: Flow hello world

(tests ; fun async tests at the REPL, see https://github.com/hyperfiddle/rcf
  "a continuous flow"
  (def !a (atom 0)) ; variable input
  (def >a (m/watch !a)) ; continuous flow of successive values of variable !a
  (def <b (m/latest inc >a)) ; map (as continuous flow, i.e. `inc` is computed on sample, pulled not pushed)

  ; Run the flow
  (def main ; a process task – run the process until it terminates
    (m/reduce ; consumer process -- consumes the flow, i.e. "flush"
      (fn [_ x] ; flow reducing function that sees each value
        (tap x)) ; tap to RCF async test queue (%)
      nil <b))

  (def cancel ; Running a task returns a cancellation callback.
    (main ; process entrypoint, a task that completes when the process terminates.
      (fn success [x] (tap x))
      (fn failure [x] (tap x))))
  ; => #object[missionary.impl.Reduce.Process]

  % := 1 ; read from RCF queue
  (swap! !a inc) ; change the variable input
  % := 2 ; consumed
  (swap! !a inc)
  % := 3
  ; process runs forever until it terminates, either naturally or due to cancellation
  (cancel) ; => nil -- send cancellation signal
  (type %) := missionary.Cancelled) ; failure callback called. FAQ: Why?

; Commentary:
; m/reduce is a typical high level way to run and consume a flow.
; Note, we never see the result of the reduce. In fact, the reduce never properly
; finishes! This is because the m/watch never naturally terminates, which is
; because the underlying atom doesn't have a notion of termination, i.e. atoms
; always have a present value and are never undefined.
; The purpose of the process entrypoint is to perform I/O, otherwise you just have
; a heater.



; Example: a flow derived from an external event producer.
; Most real world flows are driven by some foreign event producer.
; `m/observe` is the basic primitive for flow ingress (i.e. events or
; reactive values from some other system).

(tests
  "m/observe – derive a flow from foreign ingress"
  (def >a ; discrete flow of callback events
    (m/observe ; encapsulates a resource subscription with cleanup, i.e. RAII
      (fn ctor [emit!] ; flow ingest callback
        (tap "constructor") ; for allocating a resource (e.g. connection or subscription)
        (emit! ::event) ; simulated event
        (fn dtor []
          (tap "destructor"))))) ; deallocate resource here

  ; Run the flow
  (def main (->> >a (m/reduce (fn [_ x] (tap x)) nil))) ; flush
  (def cancel (main tap tap)) ; process entrypoint
  % := "constructor" ; side effect on process startup
  % := ::event
  ; this example emits only once and never again
  (cancel) ; kill process, propagate cancellation to all inputs, release resources
  % := "destructor" ; side effect on process cancellation
  (type %) := missionary.Cancelled)

; Commentary:
; m/observe is the most common way to create a flow. It is discrete, meaning
; the constructed flow is not required to have an initial value (or emit any event
; at all), though this flow does emit an initial event during the constructor,
; which is typical.
; m/observe is your basic RAII integration primitive with an imperative API which
; requires cleanup (e.g. subscribe/unsubscribe, connect/disconnect, malloc/free).
; RAII means m/observe ensures that any allocated resources are tied to the running flow's
; lifetime, i.e. you can never forget to clean it up.
; See https://en.wikipedia.org/wiki/Resource_acquisition_is_initialization.




; Example: How to use m/observe to subscribe to an atom

(defn my-watch "a teaching implementation of m/watch"
  [!x]
  (->> (m/observe ; "observe" a foreign clojure reference
         (fn ctor [emit!] ; in ctor, setup the subscriptions to the atom
           (let [k (gensym)]
             (add-watch !x k (fn [k ref old new]
                               (emit! new))) ; changes to atom are emitted as successive flow updates
             (emit! @!x) ; initial value is immediately available
             (fn dtor [] ; cleanup subscription
               (remove-watch !x k)))))
    (m/relieve {}))) ; discard stale values, DOM doesn't support backpressure

(tests
  "my-watch"
  (def !a (atom 0)) ; foreign reference
  (def <x (my-watch !a)) ; remember, <x is a value (i.e. recipe). Resource effects don't run yet!
  (def main (m/reduce (fn [_ x] (tap x)) nil <x)) ; similarly, main is a value, it hasn't run yet

  (def cancel (main (fn [_]) (fn [_]))) ; we'll no longer bother to check for
  ; successful termination. If cleanup fails there's nothing that can be done anyway.
  ; Note this fn is of arity 1, tasks complete with their terminal value.

  ; subscribe to atom
  % := 0 ; initial value
  (swap! !a inc)
  % := 1
  (swap! !a inc)
  % := 2
  (cancel)) ; unsubscribe from atom (i.e. no resource leak)

; Commentary
; Flows are values (i.e. stateless, immutable), like Haskell IO actions. Running
; a flow is analogous to unsafePerformIO. Indeed, flow composition is
; referentially transparent, even flows like this which describe an ordering of
; effects (e.g. clojure.core/add-watch, clojure.core/remove-watch, and `rcf/tap`).
; Referential transparency means you can reuse flow values, as compared to say a
; javascript Promise, which is stateful (because the result is memoized, so it's
; not a value) which means each promise object can only be run once.

(tests
  "demonstration of flow reuse"
  (def main (m/reduce (fn [_ x] (tap x)) nil <x)) ; reuse <x from above
  (def cancel1 (main (fn [_]) (fn [_]))) ; lets not just reuse <x, but also
  (def cancel2 (main (fn [_]) (fn [_]))) ; run it twice (reusing main), why not
  % := 2 ; reattach to the same reference as before, now 2 not 0
  % := 2 ; we're attached twice, both processes are watching the same atom
  (swap! !a inc)
  % := 3
  % := 3
  (cancel1)
  (cancel2))

; Commentary
; The point is not that you can reuse flows. The point is that flow computations
; are referentially transparent, which means their composition model scales to
; rich fabrics of many thousands of effects, which you can orchestrate fearlessly
; and without loss of reasoning ability. We will demonstrate this below.




; Aside: "discard"
; Discard is the fundamental operation of continuous flows, which always have a
; latest value (i.e. they nearly always discard previous values: continuous
; computations never care about anything but the lastest value).
; See https://www.dustingetz.com/#/page/signals%20vs%20streams%2C%20in%20terms%20of%20backpressure%20(2023)

(defn discard "aka {}, the default reducing function for continuous flows"
  ([acc x] x)
  ([acc] nil))

(tests
  "what is discard"
  (discard 1) := nil
  (discard 1 2) := 2
  (discard 1 nil) := nil

  "{} is exactly discard"
  ({} 1) := nil
  ({} 1 2) := 2
  ({} 1 nil) := nil)

(tests
  "discard example usage"
  (def <app (->> (m/observe (fn [!] (def emit! !) (fn cancel [])))
              (m/reductions {} ::initial) ; discard
              (m/relieve {}) ; discard
              (m/latest tap)))
  (def main (m/reduce {} nil <app)) ; discard
  (def cancel (main {} {})) ; discard discard
  % := ::initial
  (emit! ::one)
  % := ::one
  (cancel))

; Commentary
; Everybody complains about this at first. Get over it, languages have idioms
; and, as with the above 5 usages, you're going to write this all day every day.




; Example: managing a DOM element with m/observe.
; i.e. an effectful flow managing a dom element's lifecycle

(defn input-silent "managed DOM input. Ignores all events."
  [parent]
  (m/observe
    (fn mount [emit!] ; note: emit! is never called, this discrete flow emits no events!
      (let [el (.createElement js/document "input")]
        (.appendChild parent el)
        (fn unmount []
          (.removeChild parent el))))))

(tests ; run this one line by line at the REPL, so you can see the live DOM
  (def >app (input-silent js/document.body))
  (def main (m/reduce (fn [_ x] (println x)) nil >app))
  (def cancel (main {} {})) ; recall {} is pronounced "discard"
  (some? (js/document.querySelector "body > input")) := true
  ; see live DOM in attached web browser.
  ; note the flow never emits any value as we are not subscribed to DOM events.
  ; For discrete flows, that's fine. the object lifecycle is still valid.
  (cancel)
  (some? (js/document.querySelector "body > input")) := false)



; Example: managed DOM input with change values

(defn input [parent]
  (->> (m/observe
         (fn [!]
           (let [el (.createElement js/document "input")
                 on-input (fn [e] (! (.-target.value e)))]
             (.appendChild parent el)
             (.addEventListener el "input" on-input)
             (! "") ; initial value. FAQ: Why is this needed?
             (fn []
               (.removeEventListener el "input" on-input)
               (.removeChild parent el)))))
    (m/relieve {}))) ; discard stale values, don't block producer
; i.e., "never backpressure the user". Explanation further below

(tests ; run this line by line
  (def <app (input js/document.body))
  (def main (m/reduce (fn [_ x] (println x)) nil <app))
  (def cancel (main (fn [_]) (fn [_])))
  (some? (js/document.querySelector "body > input")) := true

  ; type in browser!
  ; a
  ; as
  ; asd
  ; asdf

  (cancel)
  (some? (js/document.querySelector "body > input")) := false)

; Commentary
; Why is the initial value "" needed?
; ...
; Why do we relieve backpressure?
; ...



; Example: N managed inputs.
; Demonstration of referential transparency enabling higher order composition

(tests ; run this line by line
  (def <app (let [<input (input js/document.body)]
              (apply m/latest vector (repeat 3 <input)))) ; Quiz: what will it do?
  (def main (m/reduce (fn [_ x] (println x)) nil <app))
  (def cancel (main (fn [_]) (fn [_])))
  (count (seq (js/document.querySelectorAll "body > input"))) := 2

  ; type in browser!
  ; [asdf qwer]

  (cancel))

; Commentary
; referential transparency lets us reuse the <input recipe N times.



; Topic: Backpressure! It's time
; Backpressure is the essence of Missionary. Missionary can be seen as the
; language of backpressure: a vocabulary of functional combinators that lets a
; concurrency master (that's you, this is why you're here) explicitly express and
; describe any possible async pipeline and let you fully control the backpressure
; and memory consumption at every single point. Nothing is implied, assumed or
; taken for granted. To understand Missionary is to understand Backpressure and
; to understand Backpressure is to understand Missionary.
;
; Key question: what happens if the input changes faster than the entrypoint can
; keep up with?

(defn broken-watch "same as my-watch above, but without m/relieve, and therefore discrete"
  [!x]
  (->> (m/observe
         (fn [!]
           (! @!x)
           (let [k (gensym)]
             (add-watch !x k (fn [k ref old new] (! new)))
             #(remove-watch !x k))))
    #_(m/relieve {}))) ; key difference - no relieve
; that means, since we're not discarding stale values, the consumer must accept
; every event. I switched terminology from "value" to "event" here since, without
; m/relieve, this flow forces the consumer to see each transition, i.e. event.

; So far we've been using m/reduce as our entrypoint to consume flows.
; m/reduce consumes flows immediately, i.e. as fast as they can produce values,
; reduce will consume them. So, actually we can't break anything AS LONG AS the
; entrypoint keeps up with the writer:

(tests
  "fastest possible consumer - reduce samples immediately (and blocks writers)"
  (def !a (atom 0))
  (def >a (broken-watch !a)) ; no relieve
  ; in single threaded env, consumer (reduce) blocks writer (observe)
  (def main (m/reduce (fn [_ x] (tap x)) nil >a)) ; tap every value immediately
  (def cancel (main {} {}))
  % := 0
  (do ; rapid succession
    (swap! !a inc) ; tap to RCF queue
    (swap! !a inc) ; tap
    (swap! !a inc)) ; tap
  % := 1 ; we see each value. No crash, despite forgetting to relieve
  % := 2
  % := 3
  (cancel))

; Commentary
; Since reduce is blocking the browser thread, it's actually not possible for
; some observed callback to fire faster than the entrypoint can consume. So if
; the entrypoint can't keep up, the entire application (the js runtime) will
; slow down, which blocks the callbacks as they are running in the same thread.
; BUT, what if the consumer DOES NOT keep up?


; Aside: discrete clock, which we will use to slow down the consumer

(defn clock "a discrete flow of nils, used to drive side effects"
  ([interval-ms] (clock interval-ms nil))
  ([interval-ms tick]
   (m/ap
     (loop []
       (m/amb
         (m/? (m/sleep interval-ms)) ; tick on falling edge, i.e. no initial value
         (recur))))))

(tests
  "clock, note this test is slow"
  (def main (->> (m/ap
                   (tap
                     (m/?< (clock 100))))
              (m/reduce {} nil)))
  (def cancel (main {} {}))
  (def t0 (js/performance.now))
  % := nil ; t=100ms
  % := nil ; t=200ms
  % := nil ; t=300ms
  (cancel)
  (def t1 (js/performance.now))
  (def dt (- t1 t0))
  (println dt)
  (> dt 300) := true)


; Back to backpressure

(tests
  "slower consumer"
  (def !a (atom 0))
  (def >a (broken-watch !a)) ; no relieve (i.e. discrete). Note: notationally, >a denotes discrete, <a denotes continuous
  (def main (->> (m/sample vector >a (clock 100)) ; produce a discrete flow that samples a continuous flow with a clock flow
              (m/zip tap) ; tap discretely as the sample is not available until t=100ms!
              (m/reduce {} nil)))
  (def cancel (main {} {}))
  % := [0 nil]
  (swap! !a inc) ; no problem yet
  (try
    (swap! !a inc) ; second one crashes
    (println "never get here" %) ; hoping for [2 nil]
    (catch js/Error ex
      (type ex) := js/Error
      (.-message ex) := "Can't process event - consumer is not ready."))

  (cancel))

; Commentary
; "Consumer is not ready" is a "flow protocol violation".
; Producers, once they send a value, are not allowed to send another value until
; the first value has been consumed. This makes sense and matches reality, because
; what can you do with the second value? You can either discard the stale event
; — i.e. (m/relieve [} ...) — or you can queue the events (consuming unbounded
; memory, now your app can run out of memory and requires capacity planning.
; m/relieve is the primitive that encodes what to do in this situation where the
; producer is moving faster than the consumer.

(tests
  "demonstrate consumer not ready"
  (def !a (atom 0))
  (def >a (broken-watch !a))
  (def <a (m/relieve {} >a)) ; explicitly drop stale events, now we have a "latest value"
  (def main (->> (m/sample vector <a (clock 100)) ; sample the latest value every 100ms
              (m/zip tap)
              (m/reduce {} nil)))
  (def cancel (main {} {}))

  % := [0 nil]
  (do (swap! !a inc) (swap! !a inc) (swap! !a inc)) ; rapid succession
  % := [3 nil] ; 1 and 2 were not seen, we sample the latest value! Yay
  (cancel))



; Flow Protocol -- https://github.com/leonoel/flow
; Draft, this needs substantial clarificaftion.

; To truly understand Missionary (and backpressure) you must understand the state
; transitions that continuous and discrete flows go through as the computation
; unfolds. So far, this has been hand-waved over by using m/reduce to drive the
; flows at the entrypoint (I have repeatedly mentioned "consuming them as fast
; as possible"). So let's understand exactly what this means, "to consume a flow".
; What is m/reduce doing under the hood?


(comment ; let's not use RCF for the first example as this is nuanced. Run line by line
  "low level flow protocol"
  (def !a (atom 0))
  (def >a (m/latest inc (m/watch !a))) ; real m/watch, which is equivalent to m/observe + m/relieve as above.

  ; Warning: you must run the forms *exactly* as I direct of you'll hang your
  ; session. Refresh and start over when this happens.
  ; Flows are state machines and their low level APIs are only well defined in
  ; certain states. This is the flow protocol and you MUST NOT violate it!


  ; Flows are encoded as functions. There are no Clojure protocols, i.e., the flow
  ; protocol is *dependency free* and can be implemented without dependency on Missioary.

  (fn? >a) ; => true
  (println >a)
  ; #object[Function]


  ; Missionary flows are programs. A running program is a process.
  ; (Program: stateless, static, data. Process: stateful, dynamic, control flow.)
  ; Flows happen to be encoded as thunks, none the less this is just a
  ; representation or data encoding. Thunks are immutable values.

  ; Run the flow by invoking it with two callbacks:
  (def it (>a ; run the flow, returning a process
            (fn notify [] (println ::notify)) ; called when a value is available to be sampled
            (fn terminate [] (println ::terminate)))) ; called when the process terminates

  ; :quickstart/notify -- printed at console

  ; `it` (i.e. iterator) is a handle to the running process, that you use to
  ; consume successive values as they become ready and notify.
  ; `:quickstart/notify` is printed by the notify callback right away, signalling
  ; to the downstream *consumer* (us) that the iterator (running flow) now has a
  ; value available to be *consumed*.

  ; `notify` callback is called when an input has changed, signalling that a new
  ; value is available. Here, the watch emits the initial value, which is buffered
  ; until it is pulled through. `inc` has not happened yet, because m/latest
  ; is a continous pipeline stage!

  ; Once the flow notifies, or is "ready to transfer", it has a value available
  ; to be consumed by the next stage of the pipeline.
  ; Consume the value from the iterator with `deref`:
  @it ; => 1
  ; 1 is the result of (inc 0).
  ; 0 was the initial value of the watch, which was bufferred by the watch until
  ; transferred. "transfer" means the action of the subsequent pipeline step accepting
  ; a value from the antecedent step, i.e., consumed.

  ; The process is now parked until the atom changes, at which point it will notify again.
  ; you MUST NOT deref when the flow is not in ready state, if you do you'll hang your session.

  ; Note: Manipulating raw flow iterators like this is a low level operation.
  ; Real world applications don't acutally do this, they consume with `m/reduce`
  ; instead, which abstracts over these details.

  ; When will this process next notify? When the atom changes. Let's do that now:
  (reset! !a 5)
  ; :quickstart/notify -- the running flow has notified that it is in ready state.

  ; That means we can consume the value at our convenience:
  @it ; => 6

  ; Note: we can choose NOT to consume the value immediately as well!
  ; That's the backpressure part. By not consuming, we're implicitly telling
  ; the producers that they must not try to push more values through, because
  ; we're not ready (e.g. over capacity) and can't handle it!
  ; Another example, maybe we want to only render the DOM on requestAnimationFrame.


  (swap! !a inc)
  ; :quickstart/notify
  @it ; => 7

  ; two in rapid succession, no transfer in between
  (do (swap! !a inc) (swap! !a inc))
  ; :quickstart/notify
  @it ; => 9. 8 was skipped!

  ; Q: Why didn't it crash?
  ; A: because of the m/relieve inside of m/watch

  ; Shutdown
  (it) ; => nil -- cancel
  ; :quickstart/notify -- another notify!
  ; Why? because no cleanup effects can happen until you sample!

  (try
    @it
    (catch :default e
      (js/console.log e)
      e)) ; => #object[Object [object Object]]
  ; :quickstart/terminate
  ; missionary.Cancelled {message: 'Watch cancelled.'}

  ; Q: Why the try/catch boilerplate?
  ; AL Because I knew the final value would be an exception, and the ClojureScript
  ; exception isn't serializable so gets collapsed to [Object object] en route
  ; to the remote REPL. So for teaching I opted to print it in browser with console.warn.
  )

; leonoel m/watch does relieve with discard
; it is truly equivalent to m/observe + m/relieve as you mentioned
; Dustin Getz oh, ok, so it is possible to consume all events in a single threaded context so long as the consumer keeps up and sees each value buffered by relieve. In a multi threaded context, we can miss values
; leonoel yes, that's the point of continuous time
; continuous time is resolution independence, i.e. reader decoupled from writer
; leonoel it would not make sense to crash the writers due to backpressure
; leonoel a database can always accept new transactions, even if nobody runs any query

; https://github.com/leonoel/missionary/blob/master/java/missionary/impl/Reduce.java
; https://github.com/leonoel/missionary/blob/master/java/missionary/impl/Watch.java