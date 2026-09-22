<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

use Xyp\Attribute\ListOf;
use Xyp\Date;

final readonly class Lists
{
    /**
     * @param list<string>     $names
     * @param list<mixed>      $anything
     * @param list<string>     $amounts
     * @param list<Date>       $dates
     * @param list<int>        $years
     * @param list<SampleYear> $listData
     * @param list<string>     $photos
     * @param list<bool>       $flags
     * @param list<float>      $ratios
     */
    public function __construct(
        #[ListOf('string')]
        public array $names = [],
        #[ListOf('any')]
        public array $anything = [],
        #[ListOf('decimal')]
        public array $amounts = [],
        #[ListOf('date')]
        public array $dates = [],
        #[ListOf('int')]
        public array $years = [],
        #[ListOf(SampleYear::class)]
        public array $listData = [],
        #[ListOf('bytes')]
        public array $photos = [],
        #[ListOf('bool')]
        public array $flags = [],
        #[ListOf('float')]
        public array $ratios = [],
    ) {}
}
