(require '[clojure.test :as t])

(defprotocol Either
  (left? [this])
  (right? [this])
  (invert [this])
  (-bimap [this leftf rightf]))

(declare left)
(declare right)

(deftype Left [val]
  clojure.lang.IDeref
  (deref [_] val)
  Either
  (left? [_] true)
  (right? [_] false)
  (invert [_] (right val))
  (-bimap [_ leftf _] (-> val leftf left)))

(deftype Right [val]
  clojure.lang.IDeref
  (deref [_] val)
  Either
  (left? [_] false)
  (right? [_] true)
  (invert [_] (left val))
  (-bimap [_ _ rightf] (-> val rightf right)))

(defn left
  ([] (left nil))
  ([x] (->Left x)))

(defn right
  ([] (right nil))
  ([x] (->Right x)))

;; потому, что это по определению может быть только 2 обрертки
(defn either? [x]
  (or (instance? Left x)
      (instance? Right x)))

(defn bimap [leftf rightf mv]
  (-bimap mv leftf rightf))

;; не объявлен в протоколе, т.к. это частный случай bimap
(defn map-left [f mv]
  (bimap f identity mv))

;; не объявлен в протоколе, т.к. это частный случай bimap
(defn map-right [f mv]
  (bimap identity f mv))

(defmethod print-method Left [v ^java.io.Writer w]
  (doto w
    (.write "#<Left ")
    (.write (pr-str @v))
    (.write ">")))

(defmethod print-method Right [v ^java.io.Writer w]
  (doto w
    (.write "#<Right ")
    (.write (pr-str @v))
    (.write ">")))

(t/deftest step-1
  (t/testing "constructors and deref"
    (t/testing "with value"
      (let [val :val
            l   (left val)
            r   (right val)]
        (t/is (= val @l @r))))
    (t/testing "without value"
      (let [l (left)
            r (right)]
        (t/is (= nil @l @r)))))
  (t/testing "print"
    (let [l (left)
          r (right)]
      (t/is (= "#<Left nil>" (pr-str l)))
      (t/is (= "#<Right nil>" (pr-str r)))))
  (t/testing "predicates"
    (t/testing "left?"
      (t/is (left? (left)))
      (t/is (not (left? (right)))))
    (t/testing "right?"
      (t/is (right? (right)))
      (t/is (not (right? (left)))))
    (t/testing "eihter?"
      (t/is (either? (left)))
      (t/is (either? (right)))
      (t/is (not (either? nil)))))
  (t/testing "invert"
    (let [val :val
          l   (invert (right val))
          r   (invert (left val))]
      (t/is (and (left? l) (= val @l)))
      (t/is (and (right? r) (= val @r)))))
  (t/testing "bimap"
    (let [l (->> 0 left (bimap inc identity))
          r (->> 0 right (bimap identity inc))]
      (t/is (and (left? l) (= 1 @l)))
      (t/is (and (right? r) (= 1 @r)))))
  (t/testing "map-left"
    (let [l (->> 0 left (map-left inc))
          r (->> 0 right (map-left inc))]
      (t/is (and (left? l) (= 1 @l)))
      (t/is (and (right? r) (= 0 @r)))))
  (t/testing "map-right"
    (let [l (->> 0 left (map-right inc))
          r (->> 0 right (map-right inc))]
      (t/is (and (left? l) (= 0 @l)))
      (t/is (and (right? r) (= 1 @r))))))

(defmacro let= [bindings & body]
  (assert (-> bindings count even?))
  (if (empty? bindings)
    `(let [res# (do ~@body)]
       (assert (either? res#))
       res#)
    (let [[name expr & bindings] bindings]
      `(let [val# ~expr]
         (assert (either? val#))
         (if (left? val#)
           val#
           (let [~name @val#]
             (let= [~@bindings] ~@body)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Отладка маросов
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (-> '(let= [x (left 1)
;;             y (right 2)]
;;        (right (+ x y)))
;;     macroexpand-1
;;     clojure.pprint/pprint)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest step-2
  (t/testing "let="
    (t/testing "right"
      (let [ret (let= [x (right 1)
                       y (right 2)]
                  (right (+ x y)))]
        (t/is (right? ret))
        (t/is (= 3 @ret))))
    (t/testing "left"
      (let [ret (let= [x (left 1)
                       y (right 2)]
                  (right (+ x y)))]
        (t/is (left? ret))
        (t/is (= 1 @ret))))
    (t/testing "computation"
      (t/testing "right"
        (let [effect-spy   (promise)
              side-effect! (fn [] (deliver effect-spy :ok))]
          (let= [x (right 1)
                 y (right 2)]
            (side-effect!)
            (right (+ x y)))
          (t/is (realized? effect-spy))))
      (t/testing "left"
        (let [y-spy        (promise)
              effect-spy   (promise)
              side-effect! (fn [] (deliver effect-spy :ok))]
          (let= [x (left 1)
                 y (right (do (deliver y-spy :ok) 2))]
            (side-effect!)
            (right (+ x y)))
          (t/is (not (realized? y-spy)))
          (t/is (not (realized? effect-spy))))))
    (t/testing "destructuring"
      (let [ret (let= [[x y] (right [1 2])]
                  (right (+ x y)))]
        (t/is (= 3 @ret))))
    (t/testing "asserts"
      (t/testing "bindings"
        (t/is (thrown? AssertionError
                       (let= [x 1]
                         (right x)))))
      (t/testing "result"
        (t/is (thrown? AssertionError
                       (let= [x (right 1)]
                         x)))))))

(defn >>=
  ([mv f=] (let= [v mv] (f= v)))
  ([mv f= & fs=] (reduce >>= mv (cons f= fs=))))

(defmacro >> [& mvs]
  (assert (seq mvs))
  (let [val (gensym "val")]
    `(let= [~@(interleave (repeat val) mvs)]
       (right ~val))))

(t/deftest step-3
  (t/testing ">>="
    (t/testing "right rights"
      (let [mv   (right 0)
            inc= (comp right inc)
            str= (comp right str)
            ret  (>>= mv inc= str=)]
        (t/is (right? ret))
        (t/is (= "1" @ret))))
    (t/testing "left right"
      (let [mv   (left 0)
            inc= (comp right inc)
            ret  (>>= mv inc=)]
        (t/is (left? ret))
        (t/is (= 0 @ret))))
    (t/testing "right lefts"
      (let [mv   (right 0)
            fail= (fn [_] (left :error))
            ret  (>>= mv fail=)]
        (t/is (left? ret))
        (t/is (= :error @ret)))))
  (t/testing ">>"
    (t/testing "rights"
      (let [ret (>> (right 1)
                    (right 2))]
        (t/is (right? ret))
        (t/is (= 2 @ret))))
    (t/testing "lefts"
      (let [spy (promise)
            ret (>> (left 1)
                    (right (do (deliver spy :ok) 2)))]
        (t/is (left? ret))
        (t/is (= 1 @ret))
        (t/is (not (realized? spy)))))))
