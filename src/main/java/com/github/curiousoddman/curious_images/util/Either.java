package com.github.curiousoddman.curious_images.util;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public interface Either<L, R> {

    static <L, R> Either<L, R> left(L value) {
        return new Left<>(value);
    }

    static <L, R> Either<L, R> right(R value) {
        return new Right<>(value);
    }

    boolean isLeft();

    default boolean isRight() {
        return !isLeft();
    }

    L getLeft();

    R getRight();

    <T> T map(Function<? super L, ? extends T> leftMapper,
              Function<? super R, ? extends T> rightMapper);

    default <U> Either<L, U> mapRight(Function<? super R, ? extends U> mapper) {
        return map(
                Either::left,
                r -> Either.right(mapper.apply(r))
        );
    }

    default <LL> Either<LL, R> mapLeft(Function<? super L, ? extends LL> mapper) {
        Objects.requireNonNull(mapper);

        return map(
                l -> Either.left(mapper.apply(l)),
                Either::right
        );
    }

    default <U> Either<L, U> flatMap(Function<? super R, Either<L, U>> mapper) {
        return map(
                Either::left,
                mapper
        );
    }

    default Either<R, L> swap() {
        return map(
                Either::right,
                Either::left
        );
    }

    void forEach(Consumer<Either<L, R>> leftConsumer, Consumer<Either<L, R>> rightConsumer);

    record Left<L, R>(L value) implements Either<L, R> {

        @Override
        public boolean isLeft() {
            return true;
        }

        @Override
        public L getLeft() {
            return value;
        }

        @Override
        public R getRight() {
            throw new IllegalStateException("Not a Right");
        }

        @Override
        public <T> T map(Function<? super L, ? extends T> leftMapper,
                         Function<? super R, ? extends T> rightMapper) {

            return leftMapper.apply(value);
        }

        @Override
        public void forEach(Consumer<Either<L, R>> leftConsumer, Consumer<Either<L, R>> rightConsumer) {
            leftConsumer.accept(this);
        }

        @Override
        public String toString() {
            return "Left(" + value + ")";
        }
    }

    record Right<L, R>(R value) implements Either<L, R> {

        @Override
        public boolean isLeft() {
            return false;
        }

        @Override
        public L getLeft() {
            throw new IllegalStateException("Not a Left");
        }

        @Override
        public R getRight() {
            return value;
        }

        @Override
        public <T> T map(Function<? super L, ? extends T> leftMapper,
                         Function<? super R, ? extends T> rightMapper) {

            return rightMapper.apply(value);
        }

        @Override
        public void forEach(Consumer<Either<L, R>> leftConsumer, Consumer<Either<L, R>> rightConsumer) {
            rightConsumer.accept(this);
        }

        @Override
        public String toString() {
            return "Right(" + value + ")";
        }
    }
}