определение функционального языка, отсутствие или ограничение присваивания

отсутствие присваивания и связывание? в let

```
(let [x 1
      f (fn [] x)
      x 2]
  (f)) ;; => 1
```

модель времени, атомы