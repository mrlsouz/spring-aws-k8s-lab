resource "aws_s3_bucket" "notes" {
  bucket = "mrlsouza-notes-lab-20261105-a8f3c2"

  tags = {
    Name        = "notes"
    Environment = "lab"
  }
}

resource "aws_s3_bucket_public_access_block" "notes" {
  bucket = aws_s3_bucket.notes.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
